package io.github.lilkuzcodev.warfront.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.lilkuzcodev.warfront.Warfront;
import java.io.BufferedReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

/**
 * Loads all warfront data from datapack-style JSON (architecture note 1):
 * factions (data/&lt;ns&gt;/warfront_factions/*.json), tactical templates
 * (data/&lt;ns&gt;/warfront_templates/*.json), and the config trio
 * (data/warfront/warfront_config/{relations,tech,standing}.json).
 * Adding a faction or template is dropping a JSON file — no code changes.
 */
public final class WarfrontRegistry {
	private static final Gson GSON = new Gson();

	private static Map<String, Faction> factions = Map.of();
	private static Map<String, TacticalTemplate> templates = Map.of();
	private static Map<String, String> relations = Map.of(); // "a|b" -> relation
	private static TechConfig tech = TechConfig.DEFAULT;
	private static StandingConfig standing = StandingConfig.DEFAULT;

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(Warfront.id("registry"), new Listener());
	}

	public static Map<String, Faction> factions() {
		return factions;
	}

	public static Faction faction(String id) {
		return factions.get(id);
	}

	public static Map<String, TacticalTemplate> templates() {
		return templates;
	}

	public static TechConfig tech() {
		return tech;
	}

	public static StandingConfig standing() {
		return standing;
	}

	/** Relation between two factions: "hostile", "neutral" (default), or "allied". */
	public static String relation(String a, String b) {
		if (a.equals(b)) {
			return "allied";
		}
		String rel = relations.get(a + "|" + b);
		if (rel == null) {
			rel = relations.get(b + "|" + a);
		}
		return rel == null ? "neutral" : rel;
	}

	private record LoadedData(Map<String, Faction> factions, Map<String, TacticalTemplate> templates,
			Map<String, String> relations, TechConfig tech, StandingConfig standing) {
	}

	private static class Listener extends SimpleReloadListener<LoadedData> {
		@Override
		protected LoadedData prepare(PreparableReloadListener.SharedState state) {
			ResourceManager manager = state.resourceManager();
			Map<String, Faction> factionMap = new HashMap<>();
			for (Map.Entry<Identifier, Resource> entry : manager.listResources("warfront_factions", p -> p.getPath().endsWith(".json")).entrySet()) {
				String id = fileName(entry.getKey());
				factionMap.put(id, Faction.fromJson(id, parse(entry.getValue())));
			}
			Map<String, TacticalTemplate> templateMap = new HashMap<>();
			for (Map.Entry<Identifier, Resource> entry : manager.listResources("warfront_templates", p -> p.getPath().endsWith(".json")).entrySet()) {
				String id = fileName(entry.getKey());
				templateMap.put(id, TacticalTemplate.fromJson(id, parse(entry.getValue())));
			}
			Map<String, String> relationMap = new HashMap<>();
			manager.getResource(Warfront.id("warfront_config/relations.json")).ifPresent(res -> {
				JsonObject json = parse(res);
				for (var element : GsonHelper.getAsJsonArray(json, "pairs")) {
					JsonObject pair = element.getAsJsonObject();
					relationMap.put(GsonHelper.getAsString(pair, "a") + "|" + GsonHelper.getAsString(pair, "b"),
							GsonHelper.getAsString(pair, "relation"));
				}
			});
			TechConfig techConfig = manager.getResource(Warfront.id("warfront_config/tech.json"))
					.map(res -> TechConfig.fromJson(parse(res))).orElse(TechConfig.DEFAULT);
			StandingConfig standingConfig = manager.getResource(Warfront.id("warfront_config/standing.json"))
					.map(res -> StandingConfig.fromJson(parse(res))).orElse(StandingConfig.DEFAULT);
			return new LoadedData(factionMap, templateMap, relationMap, techConfig, standingConfig);
		}

		@Override
		protected void apply(LoadedData data, PreparableReloadListener.SharedState state) {
			factions = Map.copyOf(data.factions());
			templates = Map.copyOf(data.templates());
			relations = Map.copyOf(data.relations());
			tech = data.tech();
			standing = data.standing();
			Warfront.LOGGER.info("Loaded {} factions, {} templates, {} relations", factions.size(), templates.size(), relations.size());
		}
	}

	private static String fileName(Identifier id) {
		String path = id.getPath();
		return path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
	}

	private static JsonObject parse(Resource resource) {
		try (BufferedReader reader = resource.openAsReader()) {
			return GSON.fromJson(reader, JsonObject.class);
		} catch (Exception e) {
			throw new RuntimeException("Failed to parse warfront data file", e);
		}
	}

	/** Tech curve + gates: thresholds, pacing, gear/squad/unlocks per level (levels 3-4 empty for Phase 4). */
	public record TechConfig(List<Double> levelThresholds, double pointsPerDay,
			Map<Integer, String> gearByLevel, Map<Integer, Integer> squadBonusByLevel,
			Map<Integer, List<String>> unlocksByLevel) {
		public static final TechConfig DEFAULT = new TechConfig(
				List.of(0.0, 100.0, 250.0, 450.0, 700.0), 100.0 / 30.0,
				Map.of(0, "leather", 1, "chainmail", 2, "iron", 3, "diamond", 4, "netherite"),
				Map.of(0, 0, 1, 1, 2, 2, 3, 2, 4, 3),
				Map.of(0, List.of(), 1, List.of("stations"), 2, List.of(), 3, List.of(), 4, List.of()));

		public static TechConfig fromJson(JsonObject json) {
			List<Double> thresholds = GsonHelper.getAsJsonArray(json, "level_thresholds").asList().stream()
					.map(e -> e.getAsDouble()).toList();
			Map<Integer, String> gear = new HashMap<>();
			Map<Integer, Integer> squad = new HashMap<>();
			Map<Integer, List<String>> unlocks = new HashMap<>();
			JsonObject gearJson = GsonHelper.getAsJsonObject(json, "gear_by_level");
			JsonObject squadJson = GsonHelper.getAsJsonObject(json, "squad_bonus_by_level");
			JsonObject unlocksJson = GsonHelper.getAsJsonObject(json, "unlocks_by_level");
			for (String key : gearJson.keySet()) {
				gear.put(Integer.parseInt(key), gearJson.get(key).getAsString());
			}
			for (String key : squadJson.keySet()) {
				squad.put(Integer.parseInt(key), squadJson.get(key).getAsInt());
			}
			for (String key : unlocksJson.keySet()) {
				unlocks.put(Integer.parseInt(key), unlocksJson.getAsJsonArray(key).asList().stream().map(e -> e.getAsString()).toList());
			}
			return new TechConfig(thresholds, GsonHelper.getAsDouble(json, "points_per_day"), gear, squad, unlocks);
		}

		public int levelForPoints(double points) {
			int level = 0;
			for (int i = 0; i < levelThresholds.size(); i++) {
				if (points >= levelThresholds.get(i)) {
					level = i;
				}
			}
			return level;
		}

		public boolean unlocked(int level, String feature) {
			for (int i = 0; i <= level; i++) {
				if (unlocksByLevel.getOrDefault(i, List.of()).contains(feature)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Player-standing thresholds and decay (numeric under the hood, labels derived). */
	public record StandingConfig(float hostileBelow, float waryBelow, float decayPerMinute,
			float attackPenalty, float blockPenalty) {
		public static final StandingConfig DEFAULT = new StandingConfig(-30.0F, -10.0F, 0.5F, -40.0F, -15.0F);

		public static StandingConfig fromJson(JsonObject json) {
			return new StandingConfig(
					GsonHelper.getAsFloat(json, "hostile_below"),
					GsonHelper.getAsFloat(json, "wary_below"),
					GsonHelper.getAsFloat(json, "decay_per_minute"),
					GsonHelper.getAsFloat(json, "attack_penalty"),
					GsonHelper.getAsFloat(json, "block_penalty"));
		}

		public String label(float value) {
			if (value < hostileBelow) {
				return "hostile";
			}
			return value < waryBelow ? "wary" : "neutral";
		}
	}

	private WarfrontRegistry() {
	}
}
