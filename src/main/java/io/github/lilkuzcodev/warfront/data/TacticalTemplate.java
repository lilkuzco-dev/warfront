package io.github.lilkuzcodev.warfront.data;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.util.GsonHelper;

/**
 * A tactical template declaration (data). Execution logic is looked up separately by
 * {@code executor} id — new templates reusing an existing executor are pure data
 * (architecture note 1). {@code minTechLevel} is the universal capability gate
 * (architecture note 5).
 */
public record TacticalTemplate(
		String id,
		String executor,
		int minTechLevel,
		List<String> requiredAssets,
		List<String> requiredIntel,
		List<String> constraintCompatibility,
		int costSoldiers,
		int costTimeTicks,
		float aggressionAffinity,
		float ambushAffinity) {

	public static TacticalTemplate fromJson(String id, JsonObject json) {
		JsonObject pre = GsonHelper.getAsJsonObject(json, "preconditions");
		JsonObject cost = GsonHelper.getAsJsonObject(json, "cost_estimate");
		JsonObject affinity = GsonHelper.getAsJsonObject(json, "doctrine_affinity");
		return new TacticalTemplate(
				id,
				GsonHelper.getAsString(json, "executor"),
				GsonHelper.getAsInt(pre, "min_tech_level", 0),
				stringList(pre, "assets"),
				stringList(pre, "intel"),
				stringList(json, "constraint_compatibility"),
				GsonHelper.getAsInt(cost, "soldiers", 4),
				GsonHelper.getAsInt(cost, "time_ticks", 2400),
				GsonHelper.getAsFloat(affinity, "aggression", 0.5F),
				GsonHelper.getAsFloat(affinity, "ambush_bias", 0.0F));
	}

	private static List<String> stringList(JsonObject json, String key) {
		if (!json.has(key)) {
			return List.of();
		}
		return GsonHelper.getAsJsonArray(json, key).asList().stream().map(e -> e.getAsString()).toList();
	}
}
