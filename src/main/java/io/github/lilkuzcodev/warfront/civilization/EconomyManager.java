package io.github.lilkuzcodev.warfront.civilization;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.CitizenRecord;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.CityRecord;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/** Runs the same pure model for embodied, local, and virtual citizens. */
public final class EconomyManager {
	private static final Map<String, EconomyModel> MODELS = new HashMap<>();
	private static final Map<String, Long> LAST_TICK_NANOS = new HashMap<>();
	private static final Map<String, EconomyModel.Distribution> LAST_DISTRIBUTION = new HashMap<>();

	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			MODELS.clear();
			LAST_TICK_NANOS.clear();
			LAST_DISTRIBUTION.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % WarfrontRegistry.economy().gameTicksPerEconomicTick() == 0) tick(server);
		});
	}

	private static void tick(MinecraftServer server) {
		CivilizationState civilization = CivilizationState.get(server);
		EconomyState persisted = EconomyState.get(server);
		for (CityRecord city : civilization.cities().values()) {
			long started = System.nanoTime();
			EconomyModel model = model(server, city, persisted);
			boolean fullyVirtual = city.citizens().values().stream()
					.allMatch(actor -> actor.tier() == FidelityTier.VIRTUAL);
			if (!fullyVirtual) syncPhysicalGoodsIntoModel(city, model);
			model.step();
			// The encoded economy snapshot is authoritative while every actor is Tier 3.
			// Avoid rebuilding 500 inventory maps every tick; promotion hydrates the
			// exact goods immediately before an entity is reconstituted.
			if (!fullyVirtual) projectGoods(civilization, city, model);
			persisted.put(city.id(), model);
			LAST_DISTRIBUTION.put(city.id(), model.distribution());
			LAST_TICK_NANOS.put(city.id(), System.nanoTime() - started);
		}
	}

	private static EconomyModel model(MinecraftServer server, CityRecord city, EconomyState state) {
		return MODELS.computeIfAbsent(city.id(), id -> {
			String snapshot = state.snapshot(id);
			if (snapshot != null) {
				try {
					EconomyModel loaded = EconomyModel.decode(snapshot);
					if (loaded.population() == city.citizens().size()) return loaded;
					Warfront.LOGGER.warn("Economy population changed for {}; starting a compatible model", id);
				} catch (IllegalArgumentException exception) {
					Warfront.LOGGER.error("Economy snapshot for {} failed validation; starting equal", id, exception);
				}
			}
			long seed = server.overworld().getSeed() ^ id.hashCode() * 0x9E3779B97F4A7C15L;
			int population = city.citizens().size();
			var config = WarfrontRegistry.economy();
			return new EconomyModel(new EconomyModel.Config(population, seed, config.startingWealth(),
					config.liquidityFloor(), config.fixedExchange(), population * config.exchangesPerActor(),
					config.shockInterval(), config.shockPermille()));
		});
	}

	private static void syncPhysicalGoodsIntoModel(CityRecord city, EconomyModel model) {
		for (CitizenRecord actor : city.citizens().values()) {
			int index = Math.toIntExact(actor.serial() - 1);
			if (index < 0 || index >= model.population()) continue;
			Map<EconomyModel.Good, Long> goods = new java.util.EnumMap<>(EconomyModel.Good.class);
			for (EconomyModel.Good good : EconomyModel.Good.values()) {
				goods.put(good, (long) actor.inventory().getOrDefault(itemId(good), 0));
			}
			model.setActorGoods(index, goods);
		}
	}

	private static void projectGoods(CivilizationState civilization, CityRecord city, EconomyModel model) {
		Map<String, CitizenRecord> actors = new HashMap<>();
		for (CitizenRecord actor : city.citizens().values()) {
			int index = Math.toIntExact(actor.serial() - 1);
			Map<String, Integer> inventory = new HashMap<>(actor.inventory());
			if (index >= 0 && index < model.population()) {
				for (var entry : model.actorGoods(index).entrySet()) {
					inventory.put(itemId(entry.getKey()), Math.toIntExact(entry.getValue()));
				}
			}
			actors.put(Long.toString(actor.serial()), actor.withState(actor.x(), actor.y(), actor.z(),
					actor.workTicks(), inventory, actor.lastAdvancedTick(), actor.tier()));
		}
		civilization.putCity(new CityRecord(city.id(), city.faction(), city.center(), city.radius(), city.nextSerial(),
				Map.copyOf(actors)));
	}

	public static EconomyModel.Distribution distribution(MinecraftServer server, CityRecord city) {
		EconomyModel model = model(server, city, EconomyState.get(server));
		return LAST_DISTRIBUTION.getOrDefault(city.id(), model.distribution());
	}

	public static EconomyModel.Conservation conservation(MinecraftServer server, CityRecord city) {
		return model(server, city, EconomyState.get(server)).conservation();
	}

	public static long tickNanos(String cityId) { return LAST_TICK_NANOS.getOrDefault(cityId, -1L); }
	public static long price(MinecraftServer server, CityRecord city, EconomyModel.Good good) {
		return model(server, city, EconomyState.get(server)).price(good);
	}

	public static void injectShock(MinecraftServer server, CityRecord city, EconomyModel.Shock shock) {
		EconomyModel model = model(server, city, EconomyState.get(server));
		model.injectShock(shock);
		EconomyState.get(server).put(city.id(), model);
		LAST_DISTRIBUTION.put(city.id(), model.distribution());
	}

	public static long actorMoney(MinecraftServer server, String cityId, long serial) {
		CityRecord city = CivilizationState.get(server).city(cityId);
		if (city == null) return 0;
		EconomyModel model = model(server, city, EconomyState.get(server));
		int index = Math.toIntExact(serial - 1);
		return index >= 0 && index < model.population() ? model.actorMoney(index) : 0;
	}

	public static CitizenRecord hydrateForPromotion(MinecraftServer server, CityRecord city, CitizenRecord actor) {
		EconomyModel model = model(server, city, EconomyState.get(server));
		int index = Math.toIntExact(actor.serial() - 1);
		if (index < 0 || index >= model.population()) return actor;
		Map<String, Integer> inventory = new HashMap<>(actor.inventory());
		for (var entry : model.actorGoods(index).entrySet()) {
			String item = itemId(entry.getKey());
			int count = Math.toIntExact(entry.getValue());
			if (count == 0) inventory.remove(item);
			else inventory.put(item, count);
		}
		return actor.withState(actor.x(), actor.y(), actor.z(), actor.workTicks(), Map.copyOf(inventory),
				actor.lastAdvancedTick(), actor.tier());
	}

	private static String itemId(EconomyModel.Good good) {
		return switch (good) {
			case FOOD -> "minecraft:wheat";
			case ORE -> "minecraft:raw_iron";
			case TIMBER -> "minecraft:oak_log";
			case CRAFTS -> "minecraft:oak_planks";
		};
	}

	private EconomyManager() {}
}
