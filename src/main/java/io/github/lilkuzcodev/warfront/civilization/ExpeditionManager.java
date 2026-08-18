package io.github.lilkuzcodev.warfront.civilization;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.CitizenRecord;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.CityRecord;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.Expedition;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Expeditions: the reason a city's wealth is not a closed loop.
 *
 * <p>A settlement periodically sends a party out of town. Mining brings emeralds in
 * from outside the model entirely; raiding moves wealth from another settlement into
 * this one; foraging brings back goods. Only trade is neutral. Together these are what
 * let one city become rich and another decline, rather than the whole map endlessly
 * shuffling the same fixed pile of money between neighbours.
 *
 * <p><b>Runs on the server tick and resolves from an epoch.</b> A party's outcome is a
 * pure function of its stored seed and its return tick, so an expedition dispatched
 * beside a player completes correctly even if every chunk involved is unloaded for the
 * whole journey — the failure mode this codebase has learned three times over.
 */
public final class ExpeditionManager {
	private static final int CHECK_INTERVAL = 200;

	public enum Kind { MINE, FORAGE, RAID, TRADE }

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % CHECK_INTERVAL != 0) return;
			ServerLevel level = server.overworld();
			resolveDue(server, level);
			considerDispatch(server, level);
		});
	}

	// ---------- dispatch ----------

	private static void considerDispatch(MinecraftServer server, ServerLevel level) {
		var config = WarfrontRegistry.expeditions();
		if (!config.enabled()) return;
		CivilizationState state = CivilizationState.get(server);
		long now = level.getGameTime();
		for (CityRecord city : state.cities().values()) {
			if (state.expeditionFor(city.id()) != null) continue;
			if (now - city.lastExpeditionTick() < config.cooldownTicks()) continue;
			int available = (int) city.citizens().values().stream().filter(c -> !c.isAway(now)).count();
			int party = Math.min(config.maxParty(), available / 4);
			if (party < config.minParty()) continue;

			long seed = mix(level.getSeed() ^ city.id().hashCode() * 0x9E3779B97F4A7C15L ^ now);
			Kind kind = chooseKind(server, city, seed);
			String target = kind == Kind.RAID ? pickRaidTarget(server, state, city, seed) : "";
			if (kind == Kind.RAID && target.isEmpty()) kind = Kind.MINE;

			long duration = config.baseDurationTicks()
					+ Math.floorMod(seed >>> 8, Math.max(1, config.durationJitterTicks()));
			Expedition expedition = new Expedition(city.id(), kind.name(), target, party, now,
					now + duration, seed);
			state.putExpedition(expedition);
			state.putCity(markAway(city, party, now + duration).withLastExpedition(now));
			Warfront.LOGGER.info("{} sent {} on a {} expedition{} (back in {} ticks)", city.id(), party,
					kind.name().toLowerCase(java.util.Locale.ROOT),
					target.isEmpty() ? "" : " against " + target, duration);
		}
	}

	/** What the city needs most, nudged by how aggressive its faction is. */
	private static Kind chooseKind(MinecraftServer server, CityRecord city, long seed) {
		CivilizationState state = CivilizationState.get(server);
		CityRecord current = state.city(city.id());
		if (current == null) return Kind.MINE;
		long food = EconomyManager.foodHeld(server, current);
		if (food < (long) current.citizens().size() * 2L) return Kind.FORAGE;
		var faction = WarfrontRegistry.faction(current.faction());
		double aggression = faction == null ? 0.4 : faction.doctrine().aggression();
		int roll = (int) Math.floorMod(seed >>> 16, 100L);
		if (roll < aggression * 45) return Kind.RAID;
		return roll < 80 ? Kind.MINE : Kind.TRADE;
	}

	/** The nearest settlement of a hostile faction that is worth the walk. */
	private static String pickRaidTarget(MinecraftServer server, CivilizationState state, CityRecord from,
			long seed) {
		List<String> candidates = new ArrayList<>();
		for (CityRecord other : state.cities().values()) {
			if (other.id().equals(from.id())) continue;
			if (!"hostile".equals(WarfrontRegistry.relation(from.faction(), other.faction()))) continue;
			double distance = Math.sqrt(other.center().distSqr(from.center()));
			if (distance > WarfrontRegistry.expeditions().raidRange()) continue;
			candidates.add(other.id());
		}
		if (candidates.isEmpty()) return "";
		candidates.sort(String::compareTo); // deterministic ordering before the seeded pick
		return candidates.get((int) Math.floorMod(seed >>> 24, candidates.size()));
	}

	private static CityRecord markAway(CityRecord city, int party, long until) {
		Map<String, CitizenRecord> next = new HashMap<>(city.citizens());
		int taken = 0;
		// Deterministic by serial so the same citizens are chosen on any machine.
		List<String> keys = new ArrayList<>(next.keySet());
		keys.sort(java.util.Comparator.comparingLong(Long::parseLong));
		for (String key : keys) {
			if (taken >= party) break;
			CitizenRecord actor = next.get(key);
			if (actor.awayUntilTick() > 0) continue;
			next.put(key, actor.withAwayUntil(until));
			taken++;
		}
		return city.withCitizens(next, city.nextSerial());
	}

	// ---------- resolution ----------

	private static void resolveDue(MinecraftServer server, ServerLevel level) {
		CivilizationState state = CivilizationState.get(server);
		long now = level.getGameTime();
		for (Expedition expedition : List.copyOf(state.expeditions().values())) {
			if (now < expedition.returnTick()) continue;
			resolve(server, level, state, expedition);
			state.removeExpedition(expedition.cityId());
		}
	}

	private static void resolve(MinecraftServer server, ServerLevel level, CivilizationState state,
			Expedition expedition) {
		CityRecord city = state.city(expedition.cityId());
		if (city == null) return;
		var config = WarfrontRegistry.expeditions();
		long seed = expedition.seed();
		Kind kind = kindOf(expedition.kind());
		int party = expedition.party();

		// Success is rolled once, from the stored seed: the same expedition always
		// resolves the same way no matter when the server got round to looking at it.
		int roll = (int) Math.floorMod(mix(seed ^ 0x5DEECE66DL), 100L);
		boolean success = roll < config.successPermille() / 10;
		int casualties = 0;
		String note;

		switch (kind) {
			case MINE -> {
				long emeralds = success
						? (long) party * config.mineEmeraldsPerHead()
						: (long) party * config.mineEmeraldsPerHead() / 4;
				EconomyManager.depositExternal(server, city.id(), EconomyManager.moneyOf(emeralds));
				note = "mined " + emeralds + " emeralds";
			}
			case FORAGE -> {
				long food = success ? (long) party * config.forageGoodsPerHead()
						: (long) party * config.forageGoodsPerHead() / 3;
				EconomyManager.depositGoods(server, city.id(), EconomyModel.Good.FOOD, food);
				note = "foraged " + food + " food";
			}
			case TRADE -> {
				long profit = (long) party * config.tradeEmeraldsPerHead() * (success ? 1 : 0);
				EconomyManager.depositExternal(server, city.id(), EconomyManager.moneyOf(profit));
				note = "traded for " + profit + " emeralds";
			}
			case RAID -> {
				CityRecord target = state.city(expedition.targetCityId());
				if (target == null) {
					note = "found nothing where the target had been";
					break;
				}
				if (success) {
					long wanted = EconomyManager.moneyOf((long) party * config.raidEmeraldsPerHead());
					long looted = EconomyManager.withdrawExternal(server, target.id(), wanted);
					EconomyManager.depositExternal(server, city.id(), looted);
					long grain = EconomyManager.removeGoods(server, target.id(), EconomyModel.Good.FOOD,
							(long) party * config.forageGoodsPerHead());
					EconomyManager.depositGoods(server, city.id(), EconomyModel.Good.FOOD, grain);
					casualties = defenderLosses(seed, party, config.raidCasualtyPermille());
					if (casualties > 0) killCitizens(state, target.id(), casualties, seed);
					note = "sacked " + target.id() + " for " + EconomyManager.emeraldsOf(looted)
							+ " emeralds and " + grain + " food";
				} else {
					note = "was driven off by " + target.id();
				}
			}
			default -> note = "returned";
		}

		// Attackers pay their own price whether or not they succeeded.
		int lost = defenderLosses(seed ^ 0x9E3779B9L, party,
				success ? config.raidCasualtyPermille() / 2 : config.raidCasualtyPermille());
		CityRecord after = state.city(city.id());
		if (after != null) {
			if (kind == Kind.RAID && lost > 0) killCitizens(state, city.id(), lost, seed ^ 7L);
			after = state.city(city.id());
			if (after != null) state.putCity(clearAway(after, expedition.returnTick()));
		}
		Warfront.LOGGER.info("{} expedition returned: {}{}", city.id(), note,
				kind == Kind.RAID && lost > 0 ? " (lost " + lost + ")" : "");
	}

	private static int defenderLosses(long seed, int party, int permille) {
		if (permille <= 0) return 0;
		int losses = 0;
		for (int i = 0; i < party; i++) {
			if (Math.floorMod(mix(seed + i * 31L), 1_000L) < permille) losses++;
		}
		return losses;
	}

	/** Removes citizens from a settlement; the economy retires their slots on the next tick. */
	private static void killCitizens(CivilizationState state, String cityId, int count, long seed) {
		CityRecord city = state.city(cityId);
		if (city == null) return;
		List<String> keys = new ArrayList<>(city.citizens().keySet());
		if (keys.isEmpty()) return;
		keys.sort(java.util.Comparator.comparingLong(Long::parseLong));
		Map<String, CitizenRecord> next = new HashMap<>(city.citizens());
		for (int i = 0; i < count && next.size() > 1; i++) {
			String victim = keys.get((int) Math.floorMod(mix(seed + i * 17L), keys.size()));
			next.remove(victim);
		}
		state.putCity(city.withCitizens(next, city.nextSerial()));
	}

	private static CityRecord clearAway(CityRecord city, long returnTick) {
		Map<String, CitizenRecord> next = new HashMap<>(city.citizens());
		for (Map.Entry<String, CitizenRecord> entry : next.entrySet()) {
			if (entry.getValue().awayUntilTick() == returnTick) {
				entry.setValue(entry.getValue().withAwayUntil(0L));
			}
		}
		return city.withCitizens(next, city.nextSerial());
	}

	private static Kind kindOf(String raw) {
		try {
			return Kind.valueOf(raw);
		} catch (IllegalArgumentException ignored) {
			return Kind.MINE;
		}
	}

	private static long mix(long value) {
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	private ExpeditionManager() {
	}
}
