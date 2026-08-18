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
	private static PopulationGlobal population = PopulationGlobal.DEFAULT;
	private static DispositionConfig disposition = DispositionConfig.DEFAULT;
	private static EconomyConfig economy = EconomyConfig.DEFAULT;
	private static ExpeditionConfig expeditions = ExpeditionConfig.DEFAULT;

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

	public static PopulationGlobal population() {
		return population;
	}

	public static DispositionConfig disposition() {
		return disposition;
	}

	public static EconomyConfig economy() {
		return economy;
	}

	public static ExpeditionConfig expeditions() {
		return expeditions;
	}

	/** What a settlement's parties do when they leave town, and what they bring back. */
	public record ExpeditionConfig(boolean enabled, int minParty, int maxParty, int cooldownTicks,
			int baseDurationTicks, int durationJitterTicks, int successPermille, int mineEmeraldsPerHead,
			int forageGoodsPerHead, int tradeEmeraldsPerHead, int raidEmeraldsPerHead,
			int raidCasualtyPermille, int raidRange) {
		public static final ExpeditionConfig DEFAULT = new ExpeditionConfig(
				true, 3, 12, 12_000, 6_000, 6_000, 650, 6, 12, 3, 10, 220, 1_500);

		public ExpeditionConfig {
			if (minParty < 1 || maxParty < minParty || cooldownTicks < 1 || baseDurationTicks < 1
					|| durationJitterTicks < 1 || successPermille < 0 || successPermille > 1_000
					|| mineEmeraldsPerHead < 0 || forageGoodsPerHead < 0 || tradeEmeraldsPerHead < 0
					|| raidEmeraldsPerHead < 0 || raidCasualtyPermille < 0 || raidCasualtyPermille > 1_000
					|| raidRange < 0) {
				throw new IllegalArgumentException("invalid expedition configuration");
			}
		}

		public static ExpeditionConfig fromJson(JsonObject json) {
			return new ExpeditionConfig(
					GsonHelper.getAsBoolean(json, "enabled", true),
					GsonHelper.getAsInt(json, "min_party", 3),
					GsonHelper.getAsInt(json, "max_party", 12),
					GsonHelper.getAsInt(json, "cooldown_ticks", 12_000),
					GsonHelper.getAsInt(json, "base_duration_ticks", 6_000),
					GsonHelper.getAsInt(json, "duration_jitter_ticks", 6_000),
					GsonHelper.getAsInt(json, "success_permille", 650),
					GsonHelper.getAsInt(json, "mine_emeralds_per_head", 6),
					GsonHelper.getAsInt(json, "forage_goods_per_head", 12),
					GsonHelper.getAsInt(json, "trade_emeralds_per_head", 3),
					GsonHelper.getAsInt(json, "raid_emeralds_per_head", 10),
					GsonHelper.getAsInt(json, "raid_casualty_permille", 220),
					GsonHelper.getAsInt(json, "raid_range", 1_500));
		}
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
			Map<String, String> relations, TechConfig tech, StandingConfig standing, PopulationGlobal population,
			DispositionConfig disposition, EconomyConfig economy, ExpeditionConfig expeditions) {
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
			PopulationGlobal populationConfig = manager.getResource(Warfront.id("warfront_config/population.json"))
					.map(res -> PopulationGlobal.fromJson(parse(res))).orElse(PopulationGlobal.DEFAULT);
			DispositionConfig dispositionConfig = manager.getResource(Warfront.id("warfront_config/disposition.json"))
					.map(res -> DispositionConfig.fromJson(parse(res))).orElse(DispositionConfig.DEFAULT);
			EconomyConfig economyConfig = manager.getResource(Warfront.id("warfront_config/economy.json"))
					.map(res -> EconomyConfig.fromJson(parse(res))).orElse(EconomyConfig.DEFAULT);
			ExpeditionConfig expeditionConfig = manager.getResource(Warfront.id("warfront_config/expeditions.json"))
					.map(res -> ExpeditionConfig.fromJson(parse(res))).orElse(ExpeditionConfig.DEFAULT);
			return new LoadedData(factionMap, templateMap, relationMap, techConfig, standingConfig, populationConfig,
					dispositionConfig, economyConfig, expeditionConfig);
		}

		@Override
		protected void apply(LoadedData data, PreparableReloadListener.SharedState state) {
			factions = Map.copyOf(data.factions());
			templates = Map.copyOf(data.templates());
			relations = Map.copyOf(data.relations());
			tech = data.tech();
			standing = data.standing();
			population = data.population();
			disposition = data.disposition();
			economy = data.economy();
			expeditions = data.expeditions();
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

	/** Global population budget knobs (performance-facing; per-faction flavor lives in Faction.Population). */
	public record PopulationGlobal(int perPlayerSoldierCap, int hydrationRadius, int baseTickSeconds,
			int roamIntervalSeconds, float roamChance, int outpostCitizens, int forwardBaseCitizens,
			int headquartersCitizens, int cityCitizens, int perPlayerCitizenCap,
			int townCitizens, int metropolisCitizens, int citizenHardCap, int citizensPerBunk) {
		public static final PopulationGlobal DEFAULT =
				new PopulationGlobal(64, 128, 15, 240, 0.5F, 4, 8, 14, 28, 48, 10, 300, 420, 4);

		public static PopulationGlobal fromJson(JsonObject json) {
			return new PopulationGlobal(
					GsonHelper.getAsInt(json, "per_player_soldier_cap", 64),
					GsonHelper.getAsInt(json, "hydration_radius", 128),
					GsonHelper.getAsInt(json, "base_tick_seconds", 15),
					GsonHelper.getAsInt(json, "roam_interval_seconds", 240),
					GsonHelper.getAsFloat(json, "roam_chance", 0.5F),
					GsonHelper.getAsInt(json, "outpost_citizens", 4),
					GsonHelper.getAsInt(json, "forward_base_citizens", 8),
					GsonHelper.getAsInt(json, "headquarters_citizens", 14),
					GsonHelper.getAsInt(json, "city_citizens", 28),
					GsonHelper.getAsInt(json, "per_player_citizen_cap", 48),
					GsonHelper.getAsInt(json, "town_citizens", 10),
					GsonHelper.getAsInt(json, "metropolis_citizens", 300),
					GsonHelper.getAsInt(json, "citizen_hard_cap", 420),
					GsonHelper.getAsInt(json, "citizens_per_bunk", 4));
		}

		/** Civilian population a freshly discovered structure of this tier seeds. */
		public int citizensForTier(String tier) {
			return switch (tier) {
				case "headquarters" -> headquartersCitizens;
				case "forward_base" -> forwardBaseCitizens;
				case "town" -> townCitizens;
				case "city" -> cityCitizens;
				case "metropolis" -> metropolisCitizens;
				default -> outpostCitizens;
			};
		}
	}

	/** Data-driven Phase 2 economy cadence, liquidity, exchange, and shock knobs. */
	public record EconomyConfig(int gameTicksPerEconomicTick, long startingWealth, long liquidityFloor,
			long fixedExchange, int exchangesPerActor, int shockInterval, int shockPermille,
			long moneyPerEmerald, int traderEmeraldFloat, int citizenPurseCap, int tradeLot,
			long newbornStake, int growthFoodPerCitizen, int growthIntervalTicks) {
		public static final EconomyConfig DEFAULT =
				new EconomyConfig(200, 1_000L, 100L, 3L, 1, 400, 180, 25L, 12, 4, 8, 250L, 3, 600);

		public EconomyConfig {
			if (gameTicksPerEconomicTick < 1 || startingWealth < 1 || liquidityFloor < 0
					|| fixedExchange < 1 || exchangesPerActor < 0 || shockInterval < 0
					|| shockPermille < 0 || shockPermille > 1_000 || moneyPerEmerald < 1
					|| traderEmeraldFloat < 0 || citizenPurseCap < 0 || tradeLot < 1
					|| newbornStake < 0 || growthFoodPerCitizen < 1 || growthIntervalTicks < 1) {
				throw new IllegalArgumentException("invalid economy configuration");
			}
		}

		public static EconomyConfig fromJson(JsonObject json) {
			return new EconomyConfig(
					GsonHelper.getAsInt(json, "game_ticks_per_economic_tick", 200),
					GsonHelper.getAsLong(json, "starting_wealth", 1_000L),
					GsonHelper.getAsLong(json, "liquidity_floor", 100L),
					GsonHelper.getAsLong(json, "fixed_exchange", 3L),
					GsonHelper.getAsInt(json, "exchanges_per_actor", 1),
					GsonHelper.getAsInt(json, "shock_interval", 400),
					GsonHelper.getAsInt(json, "shock_permille", 180),
					GsonHelper.getAsLong(json, "money_per_emerald", 25L),
					GsonHelper.getAsInt(json, "trader_emerald_float", 12),
					GsonHelper.getAsInt(json, "citizen_purse_cap", 4),
					GsonHelper.getAsInt(json, "trade_lot", 8),
					GsonHelper.getAsLong(json, "newborn_stake", 250L),
					GsonHelper.getAsInt(json, "growth_food_per_citizen", 3),
					GsonHelper.getAsInt(json, "growth_interval_ticks", 600));
		}
	}

	/** Player-standing thresholds and decay (numeric under the hood, labels derived). */
	public record StandingConfig(float hostileBelow, float waryBelow, float friendlyAbove, float trustedAbove,
			float decayPerMinute, float attackPenalty, float blockPenalty) {
		public static final StandingConfig DEFAULT = new StandingConfig(-30.0F, -10.0F, 25.0F, 60.0F, 0.5F, -40.0F, -15.0F);

		public static StandingConfig fromJson(JsonObject json) {
			return new StandingConfig(
					GsonHelper.getAsFloat(json, "hostile_below"),
					GsonHelper.getAsFloat(json, "wary_below"),
					GsonHelper.getAsFloat(json, "friendly_above", 25.0F),
					GsonHelper.getAsFloat(json, "trusted_above", 60.0F),
					GsonHelper.getAsFloat(json, "decay_per_minute"),
					GsonHelper.getAsFloat(json, "attack_penalty"),
					GsonHelper.getAsFloat(json, "block_penalty"));
		}

		public String label(float value) {
			if (value < hostileBelow) {
				return "hostile";
			}
			if (value < waryBelow) {
				return "wary";
			}
			if (value >= trustedAbove) {
				return "trusted";
			}
			return value >= friendlyAbove ? "friendly" : "neutral";
		}
	}

	private WarfrontRegistry() {
	}
}
