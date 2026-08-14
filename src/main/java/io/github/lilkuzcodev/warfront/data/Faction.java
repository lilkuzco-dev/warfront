package io.github.lilkuzcodev.warfront.data;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

/** A faction definition loaded from data/&lt;ns&gt;/warfront_factions/&lt;id&gt;.json. */
public record Faction(String id, String name, int primaryColor, int secondaryColor, String bannerColor, Doctrine doctrine) {

	public static Faction fromJson(String id, JsonObject json) {
		JsonObject colors = GsonHelper.getAsJsonObject(json, "colors");
		return new Faction(
				id,
				GsonHelper.getAsString(json, "name"),
				parseHex(GsonHelper.getAsString(colors, "primary")),
				parseHex(GsonHelper.getAsString(colors, "secondary")),
				GsonHelper.getAsString(colors, "banner"),
				Doctrine.fromJson(GsonHelper.getAsJsonObject(json, "doctrine")));
	}

	private static int parseHex(String hex) {
		return Integer.parseInt(hex.replace("#", ""), 16);
	}
}
