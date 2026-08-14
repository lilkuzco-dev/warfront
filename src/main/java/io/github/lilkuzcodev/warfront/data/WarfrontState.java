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

	private static final Codec<WarfrontState> CODEC = RecordCodecBuilder.create(i -> i.group(
			POINTS_CODEC.optionalFieldOf("tech_points", Map.of()).forGetter(s -> Map.copyOf(s.techPoints)),
			STANDINGS_CODEC.optionalFieldOf("standings", Map.of()).forGetter(WarfrontState::copyStandings)
	).apply(i, WarfrontState::new));

	// DataFixTypes is mandatory in the record; command storage has no legacy fixes,
	// making it the safe conventional choice for modded saved data.
	public static final SavedDataType<WarfrontState> TYPE = new SavedDataType<>(
			Warfront.id("state"), WarfrontState::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<String, Double> techPoints = new HashMap<>();
	private final Map<String, Map<String, Float>> standings = new HashMap<>();

	public WarfrontState() {
	}

	private WarfrontState(Map<String, Double> points, Map<String, Map<String, Float>> loadedStandings) {
		this.techPoints.putAll(points);
		loadedStandings.forEach((player, map) -> this.standings.put(player, new HashMap<>(map)));
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
}
