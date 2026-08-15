package io.github.lilkuzcodev.warfront.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lilkuzcodev.warfront.Warfront;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persistent world state: faction tech POINTS (the development hook — future
 * event-driven gains/losses are a simple add/subtract) and per-player per-faction
 * standing values. Station claims are intentionally in-memory only (soldiers
 * re-claim after a restart).
 */
public class WarfrontState extends SavedData {
	private static final Codec<Map<String, Double>> POINTS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);
	private static final Codec<Map<String, Map<String, Float>>> STANDINGS_CODEC =
			Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.FLOAT));
	private static final Codec<Map<String, Base>> BASES_CODEC = Codec.unboundedMap(Codec.STRING, Base.CODEC);

	private static final Codec<Map<String, Map<String, java.util.List<LedgerEvent>>>> LEDGER_CODEC =
			Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, LedgerEvent.CODEC.listOf()));
	private static final Codec<Map<String, Map<String, Contract>>> CONTRACTS_CODEC =
			Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Contract.CODEC));
	private static final Codec<Map<String, java.util.List<String>>> SHOWN_CODEC =
			Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf());
	private static final Codec<Map<String, Map<String, Long>>> USAGE_CODEC =
			Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.LONG));

	private static final Codec<WarfrontState> CODEC = RecordCodecBuilder.create(i -> i.group(
			POINTS_CODEC.optionalFieldOf("tech_points", Map.of()).forGetter(s -> Map.copyOf(s.techPoints)),
			STANDINGS_CODEC.optionalFieldOf("standings", Map.of()).forGetter(WarfrontState::copyStandings),
			BASES_CODEC.optionalFieldOf("bases", Map.of()).forGetter(s -> Map.copyOf(s.bases)),
			LEDGER_CODEC.optionalFieldOf("ledger", Map.of()).forGetter(s -> deepCopy2(s.ledger)),
			CONTRACTS_CODEC.optionalFieldOf("contracts", Map.of()).forGetter(s -> deepCopy1(s.contracts)),
			SHOWN_CODEC.optionalFieldOf("dialogue_shown", Map.of()).forGetter(s -> Map.copyOf(s.dialogueShown)),
			USAGE_CODEC.optionalFieldOf("dialogue_usage", Map.of()).forGetter(s -> deepCopy1(s.dialogueUsage))
	).apply(i, WarfrontState::new));

	private static <V> Map<String, Map<String, V>> deepCopy1(Map<String, Map<String, V>> src) {
		Map<String, Map<String, V>> copy = new HashMap<>();
		src.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
		return copy;
	}

	private static Map<String, Map<String, java.util.List<LedgerEvent>>> deepCopy2(
			Map<String, Map<String, java.util.List<LedgerEvent>>> src) {
		Map<String, Map<String, java.util.List<LedgerEvent>>> copy = new HashMap<>();
		src.forEach((p, byFaction) -> {
			Map<String, java.util.List<LedgerEvent>> inner = new HashMap<>();
			byFaction.forEach((f, list) -> inner.put(f, java.util.List.copyOf(list)));
			copy.put(p, inner);
		});
		return copy;
	}

	/** One remembered thing a player did to a faction. Weight is frozen at record time. */
	public record LedgerEvent(String type, long time, float weight) {
		public static final Codec<LedgerEvent> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("type").forGetter(LedgerEvent::type),
				Codec.LONG.fieldOf("time").forGetter(LedgerEvent::time),
				Codec.FLOAT.fieldOf("weight").forGetter(LedgerEvent::weight)
		).apply(i, LedgerEvent::new));

		public float decayed(long now, long halfLifeTicks) {
			if (halfLifeTicks <= 0) {
				return weight;
			}
			return (float) (weight * Math.pow(0.5, (now - time) / (double) halfLifeTicks));
		}
	}

	/** An accepted (or offered) work order between a player and a faction. */
	public record Contract(String orderId, String type, String targetFaction, String item, int count,
			int progress, String state, boolean penance) {
		public static final Codec<Contract> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("order_id").forGetter(Contract::orderId),
				Codec.STRING.fieldOf("type").forGetter(Contract::type),
				Codec.STRING.optionalFieldOf("target_faction", "").forGetter(Contract::targetFaction),
				Codec.STRING.optionalFieldOf("item", "").forGetter(Contract::item),
				Codec.INT.optionalFieldOf("count", 0).forGetter(Contract::count),
				Codec.INT.optionalFieldOf("progress", 0).forGetter(Contract::progress),
				Codec.STRING.optionalFieldOf("state", "offered").forGetter(Contract::state),
				Codec.BOOL.optionalFieldOf("penance", false).forGetter(Contract::penance)
		).apply(i, Contract::new));
	}

	/**
	 * A discovered faction base: registered the first time one of its garrison soldiers
	 * ticks (or a reinforcement spawns), then managed by BaseManager. Garrison is the
	 * persistent live-count — the source of truth when the base's chunks are unloaded
	 * (lazy hydration tops the entity population up to it on approach).
	 */
	public static class Base {
		public static final Codec<Base> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("faction").forGetter(b -> b.faction),
				Codec.STRING.fieldOf("tier").forGetter(b -> b.tier),
				net.minecraft.core.BlockPos.CODEC.fieldOf("center").forGetter(b -> b.center),
				Codec.INT.listOf().fieldOf("bounds").forGetter(b -> b.bounds),
				Codec.INT.fieldOf("garrison").forGetter(b -> b.garrison),
				Codec.LONG.fieldOf("last_reinforce").forGetter(b -> b.lastReinforce),
				Codec.BOOL.fieldOf("hydrated").forGetter(b -> b.hydrated)
		).apply(i, Base::new));

		public final String faction;
		public final String tier;
		public final net.minecraft.core.BlockPos center;
		public final java.util.List<Integer> bounds; // minX,minY,minZ,maxX,maxY,maxZ
		public int garrison;
		public long lastReinforce;
		public boolean hydrated;

		public Base(String faction, String tier, net.minecraft.core.BlockPos center, java.util.List<Integer> bounds,
				int garrison, long lastReinforce, boolean hydrated) {
			this.faction = faction;
			this.tier = tier;
			this.center = center;
			this.bounds = java.util.List.copyOf(bounds);
			this.garrison = garrison;
			this.lastReinforce = lastReinforce;
			this.hydrated = hydrated;
		}
	}

	// DataFixTypes is mandatory in the record; command storage has no legacy fixes,
	// making it the safe conventional choice for modded saved data.
	public static final SavedDataType<WarfrontState> TYPE = new SavedDataType<>(
			Warfront.id("state"), WarfrontState::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<String, Double> techPoints = new HashMap<>();
	private final Map<String, Map<String, Float>> standings = new HashMap<>();
	private final Map<String, Base> bases = new HashMap<>();
	private final Map<String, Map<String, java.util.List<LedgerEvent>>> ledger = new HashMap<>();
	private final Map<String, Map<String, Contract>> contracts = new HashMap<>();
	private final Map<String, java.util.List<String>> dialogueShown = new HashMap<>();
	private final Map<String, Map<String, Long>> dialogueUsage = new HashMap<>();

	public WarfrontState() {
	}

	private WarfrontState(Map<String, Double> points, Map<String, Map<String, Float>> loadedStandings,
			Map<String, Base> loadedBases, Map<String, Map<String, java.util.List<LedgerEvent>>> loadedLedger,
			Map<String, Map<String, Contract>> loadedContracts, Map<String, java.util.List<String>> loadedShown,
			Map<String, Map<String, Long>> loadedUsage) {
		this.techPoints.putAll(points);
		loadedStandings.forEach((player, map) -> this.standings.put(player, new HashMap<>(map)));
		this.bases.putAll(loadedBases);
		loadedLedger.forEach((p, byFaction) -> {
			Map<String, java.util.List<LedgerEvent>> inner = new HashMap<>();
			byFaction.forEach((f, list) -> inner.put(f, new java.util.ArrayList<>(list)));
			this.ledger.put(p, inner);
		});
		loadedContracts.forEach((p, m) -> this.contracts.put(p, new HashMap<>(m)));
		loadedShown.forEach((p, l) -> this.dialogueShown.put(p, new java.util.ArrayList<>(l)));
		loadedUsage.forEach((p, m) -> this.dialogueUsage.put(p, new HashMap<>(m)));
	}

	// ---------- bases ----------
	public Map<String, Base> bases() {
		return bases;
	}

	public Base base(String key) {
		return bases.get(key);
	}

	public void putBase(String key, Base base) {
		bases.put(key, base);
		setDirty();
	}

	public void markBasesDirty() {
		setDirty();
	}

	private Map<String, Map<String, Float>> copyStandings() {
		Map<String, Map<String, Float>> copy = new HashMap<>();
		standings.forEach((player, map) -> copy.put(player, Map.copyOf(map)));
		return copy;
	}

	public static WarfrontState get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	// ---------- tech ----------
	public double getPoints(String faction) {
		return techPoints.getOrDefault(faction, 0.0);
	}

	public void addPoints(String faction, double delta) {
		techPoints.merge(faction, delta, Double::sum);
		if (techPoints.get(faction) < 0) {
			techPoints.put(faction, 0.0);
		}
		setDirty();
	}

	public void setPointsForLevel(String faction, int level) {
		techPoints.put(faction, WarfrontRegistry.tech().levelThresholds().get(Math.clamp(level, 0, 4)));
		setDirty();
	}

	public int techLevel(String faction) {
		return WarfrontRegistry.tech().levelForPoints(getPoints(faction));
	}

	// ---------- standings ----------
	public float standing(UUID player, String faction) {
		Map<String, Float> map = standings.get(player.toString());
		return map == null ? 0.0F : map.getOrDefault(faction, 0.0F);
	}

	public void addStanding(UUID player, String faction, float delta) {
		standings.computeIfAbsent(player.toString(), k -> new HashMap<>())
				.merge(faction, delta, Float::sum);
		setDirty();
	}

	/** Decays every standing toward 0 by {@code amount}; prunes entries that reach neutral. */
	public void decayStandings(float amount) {
		for (var playerEntry : standings.values()) {
			playerEntry.replaceAll((faction, value) -> {
				if (value > 0) {
					return Math.max(0.0F, value - amount);
				}
				return Math.min(0.0F, value + amount);
			});
			playerEntry.values().removeIf(v -> v == 0.0F);
		}
		standings.values().removeIf(Map::isEmpty);
		setDirty();
	}

	public boolean isHostileTo(UUID player, String faction) {
		return standing(player, faction) < WarfrontRegistry.standing().hostileBelow();
	}

	// ---------- disposition ledger (Stage 4A) ----------

	/**
	 * Records a typed event. Applies the betrayal multiplier to negative events landed
	 * while the player was warm-or-better, and leaks a relations echo from positive
	 * events to every faction hostile to the recipient. Returns the applied weight.
	 */
	public float recordEvent(UUID player, String faction, String type, long now) {
		DispositionConfig config = WarfrontRegistry.disposition();
		DispositionConfig.EventDef def = config.events().get(type);
		if (def == null) {
			return 0;
		}
		float weight = def.weight();
		if (weight < 0) {
			String band = dispositionBand(player, faction, now);
			if ("warm".equals(band) || "friendly".equals(band) || "devoted".equals(band)) {
				weight *= config.betrayalMultiplier();
				io.github.lilkuzcodev.warfront.Warfront.LOGGER.info(
						"Betrayal: {} was {} with {}; {} weight x{}", player, band, faction, type,
						config.betrayalMultiplier());
			}
		}
		addLedgerEvent(player, faction, type, now, weight);
		if (weight > 0 && config.relationsEchoFactor() > 0) {
			for (Faction other : WarfrontRegistry.factions().values()) {
				if (!other.id().equals(faction) && "hostile".equals(WarfrontRegistry.relation(faction, other.id()))) {
					addLedgerEvent(player, other.id(), "echo", now, -weight * config.relationsEchoFactor());
				}
			}
		}
		return weight;
	}

	/** Direct ledger write with an explicit weight (echo, contract target penalties). */
	public void addLedgerEvent(UUID player, String faction, String type, long now, float weight) {
		if (weight == 0) {
			return;
		}
		ledger.computeIfAbsent(player.toString(), k -> new HashMap<>())
				.computeIfAbsent(faction, k -> new java.util.ArrayList<>())
				.add(new LedgerEvent(type, now, weight));
		setDirty();
	}

	/** Disposition score: decayed ledger sum + standing-tier baseline. */
	public float disposition(UUID player, String faction, long now) {
		DispositionConfig config = WarfrontRegistry.disposition();
		float sum = standing(player, faction) * config.standingBaselineFactor();
		Map<String, java.util.List<LedgerEvent>> byFaction = ledger.get(player.toString());
		java.util.List<LedgerEvent> events = byFaction == null ? null : byFaction.get(faction);
		if (events != null) {
			for (LedgerEvent event : events) {
				DispositionConfig.EventDef def = config.events().get(event.type());
				sum += event.decayed(now, def == null ? 48000 : def.halfLifeTicks());
			}
		}
		return sum;
	}

	public String dispositionBand(UUID player, String faction, long now) {
		return WarfrontRegistry.disposition().band(disposition(player, faction, now));
	}

	/** True if the ledger still remembers events of this type against this faction. */
	public boolean remembers(UUID player, String faction, String type, long now) {
		DispositionConfig config = WarfrontRegistry.disposition();
		Map<String, java.util.List<LedgerEvent>> byFaction = ledger.get(player.toString());
		java.util.List<LedgerEvent> events = byFaction == null ? null : byFaction.get(faction);
		if (events == null) {
			return false;
		}
		for (LedgerEvent event : events) {
			if (event.type().equals(type)) {
				DispositionConfig.EventDef def = config.events().get(type);
				if (Math.abs(event.decayed(now, def == null ? 48000 : def.halfLifeTicks())) >= config.pruneBelowAbs()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Drops ledger entries decayed below the prune threshold. Called from the minute tick. */
	public void pruneLedger(long now) {
		DispositionConfig config = WarfrontRegistry.disposition();
		for (var byFaction : ledger.values()) {
			for (var events : byFaction.values()) {
				events.removeIf(e -> {
					DispositionConfig.EventDef def = config.events().get(e.type());
					return Math.abs(e.decayed(now, def == null ? 48000 : def.halfLifeTicks())) < config.pruneBelowAbs();
				});
			}
			byFaction.values().removeIf(java.util.List::isEmpty);
		}
		ledger.values().removeIf(Map::isEmpty);
		setDirty();
	}

	/** Raw ledger view for debug/verification. */
	public java.util.List<LedgerEvent> ledgerEvents(UUID player, String faction) {
		Map<String, java.util.List<LedgerEvent>> byFaction = ledger.get(player.toString());
		java.util.List<LedgerEvent> events = byFaction == null ? null : byFaction.get(faction);
		return events == null ? java.util.List.of() : java.util.List.copyOf(events);
	}

	// ---------- work-order contracts ----------
	public Contract contract(UUID player, String faction) {
		Map<String, Contract> byFaction = contracts.get(player.toString());
		return byFaction == null ? null : byFaction.get(faction);
	}

	public void putContract(UUID player, String faction, Contract contract) {
		contracts.computeIfAbsent(player.toString(), k -> new HashMap<>()).put(faction, contract);
		setDirty();
	}

	public void clearContract(UUID player, String faction) {
		Map<String, Contract> byFaction = contracts.get(player.toString());
		if (byFaction != null) {
			byFaction.remove(faction);
			setDirty();
		}
	}

	public Map<String, Contract> contractsOf(UUID player) {
		return contracts.getOrDefault(player.toString(), Map.of());
	}

	// ---------- dialogue bookkeeping ----------
	public java.util.List<String> recentShown(UUID player) {
		return dialogueShown.getOrDefault(player.toString(), java.util.List.of());
	}

	public void markShown(UUID player, java.util.List<String> ids, int cap) {
		java.util.List<String> list = dialogueShown.computeIfAbsent(player.toString(), k -> new java.util.ArrayList<>());
		list.addAll(ids);
		while (list.size() > cap) {
			list.remove(0);
		}
		setDirty();
	}

	public long lastUsed(UUID player, String optionId) {
		Map<String, Long> usage = dialogueUsage.get(player.toString());
		return usage == null ? Long.MIN_VALUE : usage.getOrDefault(optionId, Long.MIN_VALUE);
	}

	public void markUsed(UUID player, String optionId, long now) {
		dialogueUsage.computeIfAbsent(player.toString(), k -> new HashMap<>()).put(optionId, now);
		setDirty();
	}
}
