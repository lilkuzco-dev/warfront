package io.github.lilkuzcodev.warfront.data;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.GsonHelper;

/** A faction definition loaded from data/&lt;ns&gt;/warfront_factions/&lt;id&gt;.json. */
public record Faction(String id, String name, int primaryColor, int secondaryColor, String bannerColor,
		Doctrine doctrine, Population population) {

	public static Faction fromJson(String id, JsonObject json) {
		JsonObject colors = GsonHelper.getAsJsonObject(json, "colors");
		return new Faction(
				id,
				GsonHelper.getAsString(json, "name"),
				parseHex(GsonHelper.getAsString(colors, "primary")),
				parseHex(GsonHelper.getAsString(colors, "secondary")),
				GsonHelper.getAsString(colors, "banner"),
				Doctrine.fromJson(GsonHelper.getAsJsonObject(json, "doctrine")),
				json.has("population") ? Population.fromJson(GsonHelper.getAsJsonObject(json, "population"))
						: Population.DEFAULT);
	}

	private static int parseHex(String hex) {
		return Integer.parseInt(hex.replace("#", ""), 16);
	}

	/**
	 * Per-faction population tuning (garrison sizes, reinforcement, roaming squads).
	 * Doctrine legibility lives in these numbers, so they are all data.
	 */
	public record Population(Map<String, int[]> garrison, double reinforceMinutes, int reinforcePauseRadius,
			String roamStyle, int roamSquadSize, int roamLinkBlocks, double roamDespawnUnloadedMinutes) {
		public static final Population DEFAULT = new Population(
				Map.of("outpost", new int[] { 6, 10 }, "forward_base", new int[] { 12, 18 }, "headquarters", new int[] { 20, 30 }),
				5.0, 32, "team", 3, 400, 10.0);

		public static Population fromJson(JsonObject json) {
			Map<String, int[]> garrison = new HashMap<>();
			JsonObject garrisonJson = GsonHelper.getAsJsonObject(json, "garrison");
			for (String tier : garrisonJson.keySet()) {
				var range = garrisonJson.getAsJsonArray(tier);
				garrison.put(tier, new int[] { range.get(0).getAsInt(), range.get(1).getAsInt() });
			}
			return new Population(garrison,
					GsonHelper.getAsDouble(json, "reinforce_minutes", 5.0),
					GsonHelper.getAsInt(json, "reinforce_pause_radius", 32),
					GsonHelper.getAsString(json, "roam_style", "team"),
					GsonHelper.getAsInt(json, "roam_squad_size", 3),
					GsonHelper.getAsInt(json, "roam_link_blocks", 400),
					GsonHelper.getAsDouble(json, "roam_despawn_unloaded_minutes", 10.0));
		}

		/** Stable per-base garrison target: rolls within the tier range from the base key hash. */
		public int garrisonTarget(String tier, int seed) {
			int[] range = garrison.getOrDefault(tier, new int[] { 6, 10 });
			int span = Math.max(1, range[1] - range[0] + 1);
			return range[0] + Math.floorMod(seed, span);
		}

		public long roamDespawnTicks() {
			return (long) (roamDespawnUnloadedMinutes * 1200);
		}
	}
}
