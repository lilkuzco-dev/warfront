package io.github.lilkuzcodev.warfront.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.GsonHelper;

/**
 * One authored player line (Stage 4B). Text lives in lang files (textKey); conditions
 * mirror vanilla predicate style: every present field must match, list fields are
 * any-of. The corpus is static JSON — the engine is deterministic.
 */
public record DialogueOption(String id, String textKey, String category, String responseClass,
		Conditions conditions, List<Effect> effects, int weight, String oncePer, int cooldownMinutes, boolean exit) {

	public record Conditions(List<String> factions, List<String> standings, List<String> dispositions,
			List<String> roles, List<String> locations, List<String> times, Boolean recentCombat,
			Boolean hasKilledThisFaction, List<String> contractStates, int techMin, int techMax,
			String requiresItem, int requiresCount) {
		public static final Conditions ANY = new Conditions(List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), null, null, List.of(), 0, 4, "", 0);

		static List<String> strings(JsonObject json, String key) {
			if (!json.has(key)) {
				return List.of();
			}
			JsonElement el = json.get(key);
			if (el.isJsonArray()) {
				List<String> out = new ArrayList<>();
				for (JsonElement item : el.getAsJsonArray()) {
					out.add(item.getAsString());
				}
				return out;
			}
			return List.of(el.getAsString());
		}

		public static Conditions fromJson(JsonObject json) {
			return new Conditions(
					strings(json, "faction"),
					strings(json, "standing"),
					strings(json, "disposition"),
					strings(json, "role"),
					strings(json, "location"),
					strings(json, "time"),
					json.has("recent_combat") ? json.get("recent_combat").getAsBoolean() : null,
					json.has("has_killed_this_faction") ? json.get("has_killed_this_faction").getAsBoolean() : null,
					strings(json, "active_contract"),
					GsonHelper.getAsInt(json, "tech_level_min", 0),
					GsonHelper.getAsInt(json, "tech_level_max", 4),
					GsonHelper.getAsString(json, "requires_item", ""),
					GsonHelper.getAsInt(json, "requires_count", 1));
		}
	}

	/** A dialogue consequence; type + loosely-typed params, executed by DialogueSessions. */
	public record Effect(String type, String arg, int amount, String item) {
		public static Effect fromJson(JsonObject json) {
			return new Effect(
					GsonHelper.getAsString(json, "type"),
					GsonHelper.getAsString(json, "event",
							GsonHelper.getAsString(json, "kind", GsonHelper.getAsString(json, "pool", ""))),
					GsonHelper.getAsInt(json, "amount", GsonHelper.getAsInt(json, "count", 0)),
					GsonHelper.getAsString(json, "item", ""));
		}
	}

	public static DialogueOption fromJson(String category, JsonObject json) {
		List<Effect> effects = new ArrayList<>();
		if (json.has("effects")) {
			JsonArray arr = json.getAsJsonArray("effects");
			for (JsonElement el : arr) {
				effects.add(Effect.fromJson(el.getAsJsonObject()));
			}
		}
		return new DialogueOption(
				GsonHelper.getAsString(json, "id"),
				GsonHelper.getAsString(json, "text"),
				category,
				GsonHelper.getAsString(json, "response"),
				json.has("conditions") ? Conditions.fromJson(json.getAsJsonObject("conditions")) : Conditions.ANY,
				List.copyOf(effects),
				GsonHelper.getAsInt(json, "weight", 10),
				GsonHelper.getAsString(json, "once_per", ""),
				GsonHelper.getAsInt(json, "cooldown_minutes", 0),
				GsonHelper.getAsBoolean(json, "exit", false));
	}
}
