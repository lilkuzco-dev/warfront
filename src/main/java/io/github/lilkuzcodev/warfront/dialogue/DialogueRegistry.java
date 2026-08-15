package io.github.lilkuzcodev.warfront.dialogue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.lilkuzcodev.warfront.Warfront;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

/**
 * Loads the dialogue corpora (Stage 4): player options, soldier response pools,
 * name pools, work-order pools, and quartermaster stock — all hot-reloadable via
 * /reload. Layout under data/&lt;ns&gt;/warfront_dialogue/:
 *
 * <pre>
 * options/&lt;category&gt;.json     {"category": "...", "options": [ ... ]}
 * responses/*.json            {"classes": {"class": {"faction": {"band|group": [lang keys]}}}}
 * work_orders/&lt;faction&gt;.json  {"orders": [ ... ]}
 * quartermaster/&lt;faction&gt;.json{"offers": [...], "price_multipliers": {...}}
 * </pre>
 * Names come from warfront_config/names.json.
 */
public final class DialogueRegistry {
	private static final Gson GSON = new Gson();

	private static Map<String, DialogueOption> options = Map.of();
	private static Map<String, List<DialogueOption>> optionsByCategory = Map.of();
	// responseClass -> faction -> band/group -> line keys
	private static Map<String, Map<String, Map<String, List<String>>>> responses = Map.of();
	private static Map<String, List<String>> firstNames = Map.of();
	private static Map<String, List<String>> lastNames = Map.of();
	private static Map<String, List<WorkOrder>> workOrders = Map.of();
	private static Map<String, QuartermasterStock> quartermasters = Map.of();

	public record WorkOrder(String id, String type, String targetFaction, String item, int count,
			int rewardStanding, boolean penance) {
		static WorkOrder fromJson(JsonObject json) {
			return new WorkOrder(
					GsonHelper.getAsString(json, "id"),
					GsonHelper.getAsString(json, "type"),
					GsonHelper.getAsString(json, "target_faction", ""),
					GsonHelper.getAsString(json, "item", ""),
					GsonHelper.getAsInt(json, "count", 1),
					GsonHelper.getAsInt(json, "reward_standing", 6),
					GsonHelper.getAsBoolean(json, "penance", false));
		}
	}

	public record QuartermasterStock(List<Offer> offers, Map<String, Float> standingMultiplier,
			Map<String, Float> dispositionMultiplier) {
		public record Offer(String minStanding, String costItem, int costCount, String resultItem, int resultCount) {
		}

		static QuartermasterStock fromJson(JsonObject json) {
			List<Offer> offers = new ArrayList<>();
			for (JsonElement el : GsonHelper.getAsJsonArray(json, "offers")) {
				JsonObject offer = el.getAsJsonObject();
				JsonObject cost = GsonHelper.getAsJsonObject(offer, "cost");
				JsonObject result = GsonHelper.getAsJsonObject(offer, "result");
				offers.add(new Offer(
						GsonHelper.getAsString(offer, "min_standing", "neutral"),
						GsonHelper.getAsString(cost, "item"), GsonHelper.getAsInt(cost, "count", 1),
						GsonHelper.getAsString(result, "item"), GsonHelper.getAsInt(result, "count", 1)));
			}
			Map<String, Float> standing = new HashMap<>();
			Map<String, Float> disposition = new HashMap<>();
			JsonObject mult = GsonHelper.getAsJsonObject(json, "price_multipliers", new JsonObject());
			if (mult.has("standing")) {
				for (var entry : mult.getAsJsonObject("standing").entrySet()) {
					standing.put(entry.getKey(), entry.getValue().getAsFloat());
				}
			}
			if (mult.has("disposition")) {
				for (var entry : mult.getAsJsonObject("disposition").entrySet()) {
					disposition.put(entry.getKey(), entry.getValue().getAsFloat());
				}
			}
			return new QuartermasterStock(List.copyOf(offers), standing, disposition);
		}
	}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(Warfront.id("dialogue"), new Listener());
	}

	public static Map<String, DialogueOption> options() {
		return options;
	}

	public static Map<String, List<DialogueOption>> optionsByCategory() {
		return optionsByCategory;
	}

	/**
	 * Response line keys for a class, resolved faction-first then band-exact, band-group,
	 * "neutral", any. Empty list if the class is unknown.
	 */
	public static List<String> responseLines(String responseClass, String faction, String band) {
		Map<String, Map<String, List<String>>> byFaction = responses.get(responseClass);
		if (byFaction == null) {
			return List.of();
		}
		Map<String, List<String>> byBand = byFaction.getOrDefault(faction, byFaction.get("any"));
		if (byBand == null) {
			return List.of();
		}
		List<String> lines = byBand.get(band);
		if (lines == null) {
			lines = byBand.get(io.github.lilkuzcodev.warfront.data.DispositionConfig.bandGroup(band));
		}
		if (lines == null) {
			lines = byBand.get("neutral");
		}
		if (lines == null && !byBand.isEmpty()) {
			lines = byBand.values().iterator().next();
		}
		return lines == null ? List.of() : lines;
	}

	public static Map<String, Map<String, Map<String, List<String>>>> responseClasses() {
		return responses;
	}

	/** Stable per-soldier display name from the faction name pools. */
	public static String soldierName(String faction, UUID uuid) {
		List<String> first = firstNames.getOrDefault(faction, List.of("Soldier"));
		List<String> last = lastNames.getOrDefault(faction, List.of(""));
		int hash = uuid.hashCode();
		String a = first.get(Math.floorMod(hash, first.size()));
		String b = last.isEmpty() ? "" : last.get(Math.floorMod(hash >> 8, last.size()));
		return b.isEmpty() ? a : a + " " + b;
	}

	public static List<WorkOrder> workOrders(String faction) {
		return workOrders.getOrDefault(faction, List.of());
	}

	public static WorkOrder workOrder(String faction, String id) {
		for (WorkOrder order : workOrders(faction)) {
			if (order.id().equals(id)) {
				return order;
			}
		}
		return null;
	}

	public static QuartermasterStock quartermaster(String faction) {
		return quartermasters.get(faction);
	}

	private record LoadedData(Map<String, DialogueOption> options,
			Map<String, Map<String, Map<String, List<String>>>> responses,
			Map<String, List<String>> firstNames, Map<String, List<String>> lastNames,
			Map<String, List<WorkOrder>> workOrders, Map<String, QuartermasterStock> quartermasters) {
	}

	private static class Listener extends SimpleReloadListener<LoadedData> {
		@Override
		protected LoadedData prepare(PreparableReloadListener.SharedState state) {
			ResourceManager manager = state.resourceManager();
			Map<String, DialogueOption> optionMap = new HashMap<>();
			for (var entry : manager.listResources("warfront_dialogue/options", p -> p.getPath().endsWith(".json")).entrySet()) {
				JsonObject json = parse(entry.getValue());
				String category = GsonHelper.getAsString(json, "category");
				for (JsonElement el : GsonHelper.getAsJsonArray(json, "options")) {
					DialogueOption option = DialogueOption.fromJson(category, el.getAsJsonObject());
					optionMap.put(option.id(), option);
				}
			}
			Map<String, Map<String, Map<String, List<String>>>> responseMap = new HashMap<>();
			for (var entry : manager.listResources("warfront_dialogue/responses", p -> p.getPath().endsWith(".json")).entrySet()) {
				JsonObject json = parse(entry.getValue());
				JsonObject classes = GsonHelper.getAsJsonObject(json, "classes");
				for (var classEntry : classes.entrySet()) {
					Map<String, Map<String, List<String>>> byFaction =
							responseMap.computeIfAbsent(classEntry.getKey(), k -> new HashMap<>());
					for (var factionEntry : classEntry.getValue().getAsJsonObject().entrySet()) {
						Map<String, List<String>> byBand =
								byFaction.computeIfAbsent(factionEntry.getKey(), k -> new HashMap<>());
						for (var bandEntry : factionEntry.getValue().getAsJsonObject().entrySet()) {
							List<String> lines = byBand.computeIfAbsent(bandEntry.getKey(), k -> new ArrayList<>());
							for (JsonElement line : bandEntry.getValue().getAsJsonArray()) {
								lines.add(line.getAsString());
							}
						}
					}
				}
			}
			Map<String, List<String>> first = new HashMap<>();
			Map<String, List<String>> last = new HashMap<>();
			manager.getResource(Warfront.id("warfront_config/names.json")).ifPresent(res -> {
				JsonObject json = parse(res);
				for (var factionEntry : json.entrySet()) {
					if (factionEntry.getKey().startsWith("_")) {
						continue;
					}
					JsonObject pools = factionEntry.getValue().getAsJsonObject();
					first.put(factionEntry.getKey(), toList(GsonHelper.getAsJsonArray(pools, "first")));
					last.put(factionEntry.getKey(), toList(GsonHelper.getAsJsonArray(pools, "last", new JsonArray())));
				}
			});
			Map<String, List<WorkOrder>> orderMap = new HashMap<>();
			for (var entry : manager.listResources("warfront_dialogue/work_orders", p -> p.getPath().endsWith(".json")).entrySet()) {
				String faction = fileName(entry.getKey());
				List<WorkOrder> orders = new ArrayList<>();
				for (JsonElement el : GsonHelper.getAsJsonArray(parse(entry.getValue()), "orders")) {
					orders.add(WorkOrder.fromJson(el.getAsJsonObject()));
				}
				orderMap.put(faction, List.copyOf(orders));
			}
			Map<String, QuartermasterStock> stockMap = new HashMap<>();
			for (var entry : manager.listResources("warfront_dialogue/quartermaster", p -> p.getPath().endsWith(".json")).entrySet()) {
				stockMap.put(fileName(entry.getKey()), QuartermasterStock.fromJson(parse(entry.getValue())));
			}
			return new LoadedData(optionMap, responseMap, first, last, orderMap, stockMap);
		}

		@Override
		protected void apply(LoadedData data, PreparableReloadListener.SharedState state) {
			options = Map.copyOf(data.options());
			Map<String, List<DialogueOption>> byCategory = new HashMap<>();
			for (DialogueOption option : options.values()) {
				byCategory.computeIfAbsent(option.category(), k -> new ArrayList<>()).add(option);
			}
			optionsByCategory = Map.copyOf(byCategory);
			responses = data.responses();
			firstNames = data.firstNames();
			lastNames = data.lastNames();
			workOrders = data.workOrders();
			quartermasters = data.quartermasters();
			Warfront.LOGGER.info("Loaded dialogue: {} options in {} categories, {} response classes, {} order pools",
					options.size(), optionsByCategory.size(), responses.size(), workOrders.size());
		}
	}

	private static List<String> toList(JsonArray array) {
		List<String> out = new ArrayList<>();
		for (JsonElement el : array) {
			out.add(el.getAsString());
		}
		return out;
	}

	private static String fileName(Identifier id) {
		String path = id.getPath();
		return path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
	}

	private static JsonObject parse(Resource resource) {
		try (BufferedReader reader = resource.openAsReader()) {
			return GSON.fromJson(reader, JsonObject.class);
		} catch (Exception e) {
			throw new RuntimeException("Failed to parse warfront dialogue file", e);
		}
	}

	private DialogueRegistry() {
	}
}
