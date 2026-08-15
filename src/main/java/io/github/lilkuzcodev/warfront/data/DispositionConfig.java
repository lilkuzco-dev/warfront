package io.github.lilkuzcodev.warfront.data;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.GsonHelper;

/**
 * The dialogue memory model (v0.2.0 Stage 4A): typed ledger events with per-type
 * weights and decay half-lives, disposition band thresholds, the betrayal multiplier,
 * and the relations echo. All numbers from warfront_config/disposition.json.
 */
public record DispositionConfig(Map<String, EventDef> events, float vengefulBelow, float hostileBelow,
		float coldBelow, float warmAbove, float friendlyAbove, float devotedAbove,
		float standingBaselineFactor, float relationsEchoFactor, float betrayalMultiplier, float pruneBelowAbs) {

	public static final List<String> BANDS =
			List.of("vengeful", "hostile", "cold", "neutral", "warm", "friendly", "devoted");

	public record EventDef(float weight, double halfLifeDays) {
		public long halfLifeTicks() {
			return (long) (halfLifeDays * 24000);
		}
	}

	public static final DispositionConfig DEFAULT = new DispositionConfig(
			Map.of("killed_soldier", new EventDef(-15, 3), "attacked_soldier", new EventDef(-6, 3),
					"traded", new EventDef(4, 1), "echo", new EventDef(0, 2)),
			-60, -35, -12, 12, 35, 70, 0.25F, 0.35F, 2.0F, 0.5F);

	public static DispositionConfig fromJson(JsonObject json) {
		Map<String, EventDef> events = new HashMap<>();
		JsonObject eventsJson = GsonHelper.getAsJsonObject(json, "events");
		for (String type : eventsJson.keySet()) {
			JsonObject def = eventsJson.getAsJsonObject(type);
			events.put(type, new EventDef(GsonHelper.getAsFloat(def, "weight"),
					GsonHelper.getAsDouble(def, "half_life_days")));
		}
		JsonObject bands = GsonHelper.getAsJsonObject(json, "bands");
		return new DispositionConfig(events,
				GsonHelper.getAsFloat(bands, "vengeful_below"),
				GsonHelper.getAsFloat(bands, "hostile_below"),
				GsonHelper.getAsFloat(bands, "cold_below"),
				GsonHelper.getAsFloat(bands, "warm_above"),
				GsonHelper.getAsFloat(bands, "friendly_above"),
				GsonHelper.getAsFloat(bands, "devoted_above"),
				GsonHelper.getAsFloat(json, "standing_baseline_factor", 0.25F),
				GsonHelper.getAsFloat(json, "relations_echo_factor", 0.35F),
				GsonHelper.getAsFloat(json, "betrayal_multiplier", 2.0F),
				GsonHelper.getAsFloat(json, "prune_below_abs", 0.5F));
	}

	public String band(float score) {
		if (score < vengefulBelow) {
			return "vengeful";
		}
		if (score < hostileBelow) {
			return "hostile";
		}
		if (score < coldBelow) {
			return "cold";
		}
		if (score >= devotedAbove) {
			return "devoted";
		}
		if (score >= friendlyAbove) {
			return "friendly";
		}
		return score >= warmAbove ? "warm" : "neutral";
	}

	/** Coarse grouping for response pools: negative / neutral / positive. */
	public static String bandGroup(String band) {
		return switch (band) {
			case "vengeful", "hostile", "cold" -> "negative";
			case "warm", "friendly", "devoted" -> "positive";
			default -> "neutral";
		};
	}
}
