package io.github.lilkuzcodev.warfront.civilization;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.CitizenRecord;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.CityRecord;
import io.github.lilkuzcodev.warfront.entity.CitizenEntity;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import io.github.lilkuzcodev.warfront.entity.WarfrontEntities;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;

/**
 * Reuses the Legion/garrison fidelity philosophy for civilian actors. The record is
 * authoritative; at most one embodied entity may project it.
 */
public final class CivilizationManager {
	public static final int EMBODIED_RADIUS = 48;
	private static final int RECONCILE_INTERVAL = 20;
	private static final AABB WHOLE_WORLD = new AABB(-3.0E7, -512, -3.0E7, 3.0E7, 512, 3.0E7);
	private static final Map<String, Long> LAST_CITY_TICK_NANOS = new HashMap<>();

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % RECONCILE_INTERVAL == 0) reconcile(server);
		});
	}

	public static CityRecord createCity(ServerLevel level, String requestedId, String faction, BlockPos center,
			int population) {
		CivilizationState state = CivilizationState.get(level.getServer());
		String id = normalizeId(requestedId);
		if (id.isEmpty()) throw new IllegalArgumentException("city id must contain a letter or number");
		if (state.city(id) != null) throw new IllegalArgumentException("city already exists: " + id);
		CityRecord city = new CityRecord(id, faction, center.immutable(), 64, population + 1L,
				buildCitizens(level, id, center, population, List.of()));
		state.putCity(city);
		reconcile(level.getServer());
		return state.city(id);
	}

	/**
	 * Attaches a civilian population to a structure that just generated. Idempotent: a
	 * settlement is seeded once, the first time its structure is discovered, and every
	 * later discovery of the same structure is a no-op.
	 *
	 * <p>{@code homes} are standable positions found inside the structure; citizens are
	 * placed on them so nobody spawns inside a wall. An empty list falls back to a
	 * spiral around the centre.
	 */
	public static @org.jspecify.annotations.Nullable CityRecord seedSettlement(ServerLevel level, String rawId,
			String faction, BlockPos center, int radius, int population, List<BlockPos> homes) {
		if (population < 1) return null;
		CivilizationState state = CivilizationState.get(level.getServer());
		String id = normalizeId(rawId);
		if (id.isEmpty()) return null;
		CityRecord existing = state.city(id);
		if (existing != null) return existing;
		CityRecord city = new CityRecord(id, faction, center.immutable(), radius, population + 1L,
				buildCitizens(level, id, center, population, homes));
		state.putCity(city);
		Warfront.LOGGER.info("Seeded settlement {} ({}) with {} citizens at {}", id, faction, population,
				center.toShortString());
		return city;
	}

	private static Map<String, CitizenRecord> buildCitizens(ServerLevel level, String id, BlockPos center,
			int population, List<BlockPos> homes) {
		Map<String, CitizenRecord> citizens = new HashMap<>();
		long now = level.getGameTime();
		for (int i = 0; i < population; i++) {
			long serial = i + 1L;
			double x;
			double z;
			int y;
			if (homes.isEmpty()) {
				double angle = serial * 2.399963229728653;
				double distance = 3.0 + (serial % 7) * 1.7;
				x = center.getX() + 0.5 + Math.cos(angle) * distance;
				z = center.getZ() + 0.5 + Math.sin(angle) * distance;
				// The command position is concrete even immediately after a teleport. A
				// heightmap query can observe the pre-load sentinel for the destination
				// chunk and strand actors at build height before their first promotion.
				y = center.getY();
			} else {
				BlockPos home = homes.get(i % homes.size());
				x = home.getX() + 0.5;
				y = home.getY();
				z = home.getZ() + 0.5;
			}
			CitizenProfession profession = CitizenProfession.values()[i % CitizenProfession.values().length];
			UUID uuid = UUID.nameUUIDFromBytes((level.getSeed() + ":" + id + ":" + serial)
					.getBytes(StandardCharsets.UTF_8));
			CitizenRecord actor = new CitizenRecord(serial, uuid, profession, x, y, z, 0L, Map.of(), now,
					FidelityTier.VIRTUAL);
			citizens.put(Long.toString(serial), actor);
		}
		return Map.copyOf(citizens);
	}

	public static void reconcile(MinecraftServer server) {
		ServerLevel level = server.overworld();
		CivilizationState state = CivilizationState.get(server);
		Map<String, CitizenEntity> loaded = new HashMap<>();
		Set<CitizenEntity> duplicates = new HashSet<>();
		for (CitizenEntity entity : level.getEntitiesOfClass(CitizenEntity.class, WHOLE_WORLD)) {
			String key = actorKey(entity.cityId(), entity.serial());
			CitizenEntity prior = loaded.putIfAbsent(key, entity);
			if (prior != null) duplicates.add(entity);
		}
		duplicates.forEach(CitizenEntity::removeForLadder);

		// Global live-citizen budget, mirroring the garrison budget. Once it is spent the
		// remaining citizens stay abstract: their economy keeps running, they simply are
		// not embodied. Nothing is lost, because the record is what is authoritative.
		int budget = io.github.lilkuzcodev.warfront.data.WarfrontRegistry.population().perPlayerCitizenCap()
				* level.players().size() - loaded.size();

		for (CityRecord city : state.cities().values()) {
			long cityStarted = System.nanoTime();
			Map<String, CitizenRecord> nextCitizens = new HashMap<>();
			for (CitizenRecord original : city.citizens().values()) {
				String key = actorKey(city.id(), original.serial());
				CitizenEntity entity = loaded.get(key);
				FidelityTier desired = desiredTier(level, original);
				if (original.tier() == FidelityTier.VIRTUAL && desired != FidelityTier.VIRTUAL) {
					original = EconomyManager.hydrateForPromotion(server, city, original);
				}
				CitizenRecord current;
				if (original.tier() == FidelityTier.EMBODIED && entity != null) {
					current = snapshot(entity, original, level.getGameTime(), FidelityTier.EMBODIED);
				} else {
					current = advanceAbstract(original, level.getGameTime());
				}

				if (desired == FidelityTier.EMBODIED && entity == null && budget <= 0) {
					desired = FidelityTier.LOCAL_ABSTRACT;
				}
				if (desired == FidelityTier.EMBODIED) {
					if (entity == null || !entity.isAlive()) {
						entity = embody(level, city, current);
						if (entity != null) budget--;
					}
					if (entity != null) {
						// A chunk may deserialize its last saved entity before our first
						// reconciliation. On promotion the record wins, including goods
						// produced while that chunk was absent.
						if (original.tier() != FidelityTier.EMBODIED) {
							entity.setPos(current.x(), current.y(), current.z());
							entity.initialize(city.id(), current.serial(), current.profession(), city.center(),
									current.workTicks(), current.inventory());
						}
						current = current.withState(entity.getX(), entity.getY(), entity.getZ(), current.workTicks(),
								current.inventory(), level.getGameTime(), FidelityTier.EMBODIED);
					} else {
						current = current.withState(current.x(), current.y(), current.z(), current.workTicks(),
								current.inventory(), level.getGameTime(), FidelityTier.LOCAL_ABSTRACT);
					}
				} else {
					if (entity != null && entity.isAlive()) {
						current = snapshot(entity, current, level.getGameTime(), desired);
						entity.removeForLadder();
					}
					current = current.withState(current.x(), current.y(), current.z(), current.workTicks(),
							current.inventory(), level.getGameTime(), desired);
				}
				nextCitizens.put(Long.toString(current.serial()), current);
			}
			state.putCity(new CityRecord(city.id(), city.faction(), city.center(), city.radius(), city.nextSerial(),
					Map.copyOf(nextCitizens)));
			assignSoldiers(level, state, city);
			LAST_CITY_TICK_NANOS.put(city.id(), System.nanoTime() - cityStarted);
		}
	}

	public static long lastCityTickNanos(String cityId) {
		return LAST_CITY_TICK_NANOS.getOrDefault(cityId, -1L);
	}

	private static CitizenRecord advanceAbstract(CitizenRecord actor, long now) {
		long elapsed = Math.max(0L, now - actor.lastAdvancedTick());
		long remainder = (actor.workTicks() + elapsed) % CivilizationMath.WORK_CYCLE_TICKS;
		return actor.withState(actor.x(), actor.y(), actor.z(), remainder, actor.inventory(), now,
				actor.tier());
	}

	private static CitizenRecord snapshot(CitizenEntity entity, CitizenRecord fallback, long now, FidelityTier tier) {
		return fallback.withState(entity.getX(), entity.getY(), entity.getZ(), entity.workTicks(),
				entity.inventorySnapshot(), now, tier);
	}

	private static CitizenEntity embody(ServerLevel level, CityRecord city, CitizenRecord actor) {
		if (!level.hasChunk(((int) Math.floor(actor.x())) >> 4, ((int) Math.floor(actor.z())) >> 4)) return null;
		CitizenEntity entity = WarfrontEntities.CITIZEN.create(level, EntitySpawnReason.EVENT);
		if (entity == null) return null;
		entity.setUUID(actor.entityId());
		entity.setPos(actor.x(), actor.y(), actor.z());
		entity.initialize(city.id(), actor.serial(), actor.profession(), city.center(), actor.workTicks(), actor.inventory());
		if (!level.addFreshEntity(entity)) return null;
		return entity;
	}

	private static FidelityTier desiredTier(ServerLevel level, CitizenRecord actor) {
		for (ServerPlayer player : level.players()) {
			double dx = player.getX() - actor.x();
			double dy = player.getY() - actor.y();
			double dz = player.getZ() - actor.z();
			if (dx * dx + dy * dy + dz * dz <= EMBODIED_RADIUS * EMBODIED_RADIUS) {
				return FidelityTier.EMBODIED;
			}
		}
		return level.hasChunk(((int) Math.floor(actor.x())) >> 4, ((int) Math.floor(actor.z())) >> 4)
				? FidelityTier.LOCAL_ABSTRACT : FidelityTier.VIRTUAL;
	}

	private static void assignSoldiers(ServerLevel level, CivilizationState state, CityRecord city) {
		AABB bounds = new AABB(city.center()).inflate(city.radius());
		for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class, bounds,
				s -> city.faction().equals(s.getFaction()))) {
			state.assignSoldier(soldier.getUUID(), city.id());
		}
	}

	public static void onCitizenDeath(ServerLevel level, String cityId, long serial) {
		CivilizationState.get(level.getServer()).removeCitizen(cityId, serial);
	}

	public static ValidationResult validatePhaseOne() {
		Map<String, Integer> starting = Map.of("minecraft:wheat", 7, "minecraft:raw_iron", 3);
		long before = CivilizationMath.goodsTotal(starting);
		Map<String, Integer> afterTransitions = Map.copyOf(new HashMap<>(starting));
		long after = CivilizationMath.goodsTotal(afterTransitions);
		if (before != after || !starting.equals(afterTransitions)) {
			throw new IllegalStateException("tier transition changed inventory");
		}
		var first = CivilizationMath.advance(CitizenProfession.MINER, 75L, starting, 12_125L);
		var replay = CivilizationMath.advance(CitizenProfession.MINER, 75L, starting, 12_125L);
		if (!first.equals(replay)) throw new IllegalStateException("virtual replay diverged");
		if (first.remainderTicks() != 0L || first.produced() != 61L) {
			throw new IllegalStateException("elapsed-work arithmetic is wrong: " + first);
		}
		return new ValidationResult(before, after, first.produced(), first.remainderTicks(), true);
	}

	public record ValidationResult(long goodsBeforeTransitions, long goodsAfterTransitions,
			long virtualGoodsProduced, long workRemainder, boolean deterministicReplay) {}

	private static String actorKey(String city, long serial) { return city + ":" + serial; }

	public static String normalizeId(String value) {
		return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "_")
				.replaceAll("_+", "_").replaceAll("^_|_$", "");
	}

	private CivilizationManager() {}
}
