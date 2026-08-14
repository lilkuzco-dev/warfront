package io.github.lilkuzcodev.warfront.data;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

/**
 * A faction's doctrine weight block — pure data, no logic (architecture note 1).
 * Every behavioral system reads these weights; none of them live in code.
 */
public record Doctrine(
		float aggression,
		float casualtyTolerance,
		int preferredSquadSize,
		int flankVectors,
		float ambushBias,
		float nightBias,
		float retreatThreshold,
		float techRate,
		int gearBonus,
		float squadGrowth,
		float ambushGrowth) {

	public static Doctrine fromJson(JsonObject json) {
		return new Doctrine(
				GsonHelper.getAsFloat(json, "aggression"),
				GsonHelper.getAsFloat(json, "casualty_tolerance"),
				GsonHelper.getAsInt(json, "preferred_squad_size"),
				GsonHelper.getAsInt(json, "flank_vectors"),
				GsonHelper.getAsFloat(json, "ambush_bias"),
				GsonHelper.getAsFloat(json, "night_bias"),
				GsonHelper.getAsFloat(json, "retreat_threshold"),
				GsonHelper.getAsFloat(json, "tech_rate"),
				GsonHelper.getAsInt(json, "gear_bonus", 0),
				GsonHelper.getAsFloat(json, "squad_growth", 1.0F),
				GsonHelper.getAsFloat(json, "ambush_growth", 0.0F));
	}
}
