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
			// Births and expeditions run off the same economic tick, so a town keeps
			// growing and its parties keep marching while nobody is anywhere near it.
			CityRecord current = civilization.city(city.id());
			if (current != null) {
				CityRecord grown = CityGrowth.maybeGrow(server.overworld(), current, model);
				if (grown != current) civilization.putCity(grown);
			}
			persisted.put(city.id(), model);
			LAST_DISTRIBUTION.put(city.id(), model.distribution());
			LAST_TICK_NANOS.put(city.id(), System.nanoTime() - started);
		}
	}

	private static EconomyModel model(MinecraftServer server, CityRecord city, EconomyState state) {
		EconomyModel model = MODELS.computeIfAbsent(city.id(), id -> load(server, city, state, id));
		// A city gains people by birth and loses them to war, so the model's roster is
		// brought into line with the record instead of being thrown away and restarted
		// (which used to wipe every citizen's accumulated wealth on a population change).
		reconcileActors(model, city);
		return model;
	}

	/** Grows a slot for every living citizen and retires the slots of the departed. */
	private static void reconcileActors(EconomyModel model, CityRecord city) {
		long stake = WarfrontRegistry.economy().newbornStake();
		boolean[] present = new boolean[Math.max(model.population(), highestSerial(city))];
		for (CitizenRecord actor : city.citizens().values()) {
			int index = Math.toIntExact(actor.serial() - 1);
			if (index < 0) continue;
			if (index < present.length) present[index] = true;
			if (!model.isActive(index)) model.bringActorToLife(index, stake);
		}
		for (int index = 0; index < model.population(); index++) {
			if (model.isActive(index) && (index >= present.length || !present[index])) {
				model.retireActor(index);
			}
		}
	}

	private static int highestSerial(CityRecord city) {
		int highest = 0;
		for (CitizenRecord actor : city.citizens().values()) {
			highest = Math.max(highest, Math.toIntExact(actor.serial()));
		}
		return highest;
	}

	private static EconomyModel load(MinecraftServer server, CityRecord city, EconomyState state, String id) {
		{
			String snapshot = state.snapshot(id);
			if (snapshot != null) {
				try {
					return EconomyModel.decode(snapshot);
				} catch (IllegalArgumentException exception) {
					Warfront.LOGGER.error("Economy snapshot for {} failed validation; starting equal", id, exception);
				}
			}
			long seed = server.overworld().getSeed() ^ id.hashCode() * 0x9E3779B97F4A7C15L;
			int population = Math.max(1, city.citizens().size());
			var config = WarfrontRegistry.economy();
			return new EconomyModel(new EconomyModel.Config(population, seed, config.startingWealth(),
					config.liquidityFloor(), config.fixedExchange(), population * config.exchangesPerActor(),
					config.shockInterval(), config.shockPermille()));
		}
	}

	// ---------- the open economy: what expeditions bring home ----------

	/** Wealth carried into a city from outside it. Returns false if the city is gone. */
	public static boolean depositExternal(MinecraftServer server, String cityId, long money) {
		CityRecord city = CivilizationState.get(server).city(cityId);
		if (city == null) return false;
		EconomyModel model = model(server, city, EconomyState.get(server));
		model.depositExternal(money);
		EconomyState.get(server).put(cityId, model);
		return true;
	}

	/** Wealth taken out of a city. Returns how much was actually there to take. */
	public static long withdrawExternal(MinecraftServer server, String cityId, long money) {
		CityRecord city = CivilizationState.get(server).city(cityId);
		if (city == null) return 0L;
		EconomyModel model = model(server, city, EconomyState.get(server));
		long taken = model.withdrawExternal(money);
		EconomyState.get(server).put(cityId, model);
		return taken;
	}

	public static void depositGoods(MinecraftServer server, String cityId, EconomyModel.Good good, long amount) {
		CityRecord city = CivilizationState.get(server).city(cityId);
		if (city == null) return;
		EconomyModel model = model(server, city, EconomyState.get(server));
		model.depositGoods(good, amount);
		EconomyState.get(server).put(cityId, model);
	}

	public static long removeGoods(MinecraftServer server, String cityId, EconomyModel.Good good, long amount) {
		CityRecord city = CivilizationState.get(server).city(cityId);
		if (city == null) return 0L;
		EconomyModel model = model(server, city, EconomyState.get(server));
		long taken = model.removeGoods(good, amount);
		EconomyState.get(server).put(cityId, model);
		return taken;
	}

	public static long treasury(MinecraftServer server, CityRecord city) {
		return model(server, city, EconomyState.get(server)).treasury();
	}

	public static long foodHeld(MinecraftServer server, CityRecord city) {
		return model(server, city, EconomyState.get(server)).foodHeld();
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
				putPurse(inventory, actor.profession(), model.actorMoney(index));
			}
			actors.put(Long.toString(actor.serial()), actor.withState(actor.x(), actor.y(), actor.z(),
					actor.workTicks(), inventory, actor.lastAdvancedTick(), actor.tier()));
		}
		civilization.putCity(city.withCitizens(actors, city.nextSerial()));
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
		putPurse(inventory, actor.profession(), model.actorMoney(index));
		return actor.withState(actor.x(), actor.y(), actor.z(), actor.workTicks(), Map.copyOf(inventory),
				actor.lastAdvancedTick(), actor.tier());
	}

	/** The carried-emerald float is derived state: recomputed, never accumulated. */
	private static void putPurse(Map<String, Integer> inventory, CitizenProfession profession, long money) {
		int emeralds = purse(profession, money);
		if (emeralds <= 0) inventory.remove(EMERALD);
		else inventory.put(EMERALD, emeralds);
	}

	// ---------- emeralds: the visible face of the model's abstract money ----------

	public static final String EMERALD = "minecraft:emerald";

	/** Whole emeralds a money balance is worth, at the datapack exchange rate. */
	public static long emeraldsOf(long money) {
		return money / WarfrontRegistry.economy().moneyPerEmerald();
	}

	/** Money value of a number of emeralds — the inverse of {@link #emeraldsOf}. */
	public static long moneyOf(long emeralds) {
		return emeralds * WarfrontRegistry.economy().moneyPerEmerald();
	}

	/**
	 * Emeralds a citizen physically carries. Deliberately a small capped float rather
	 * than their whole balance: wealth stays abstract holdings, so a citizen is visible
	 * evidence of the economy without being an emerald farm.
	 */
	public static int purse(CitizenProfession profession, long money) {
		var config = WarfrontRegistry.economy();
		int cap = profession == CitizenProfession.TRADER
				? config.traderEmeraldFloat() : config.citizenPurseCap();
		return (int) Math.clamp(emeraldsOf(money), 0L, cap);
	}

	/** The good a tradeable item stands for, or null if the citizens do not deal in it. */
	public static EconomyModel.@org.jspecify.annotations.Nullable Good goodOfItem(String itemId) {
		for (EconomyModel.Good good : EconomyModel.Good.values()) {
			if (itemId(good).equals(itemId)) return good;
		}
		return null;
	}

	public static String itemOf(EconomyModel.Good good) {
		return itemId(good);
	}

	/** Emerald price for one lot, rounded so the citizen never trades at a loss. */
	public static long lotPriceEmeralds(MinecraftServer server, CityRecord city, EconomyModel.Good good,
			boolean playerIsBuying) {
		long unit = price(server, city, good);
		long lot = WarfrontRegistry.economy().tradeLot();
		long money = unit * lot;
		long perEmerald = WarfrontRegistry.economy().moneyPerEmerald();
		// Buying rounds up and selling rounds down: the spread is the citizen's margin.
		long emeralds = playerIsBuying
				? (money + perEmerald - 1) / perEmerald
				: money / perEmerald;
		return Math.max(1L, emeralds);
	}

	/** Prices an exact quantity of a tangible item; unknown drops use one emerald per configured lot. */
	public static long physicalSalePriceEmeralds(MinecraftServer server, CityRecord city,
			String itemId, int quantity) {
		if (quantity < 1) return 1L;
		EconomyModel.Good good = goodOfItem(itemId);
		long perEmerald = WarfrontRegistry.economy().moneyPerEmerald();
		long unitMoney = good == null
				? Math.max(1L, perEmerald / WarfrontRegistry.economy().tradeLot())
				: price(server, city, good);
		long money = Math.multiplyExact(unitMoney, quantity);
		return Math.max(1L, (money + perEmerald - 1L) / perEmerald);
	}

	/** Records emeralds paid to an embodied worker; physical stock is reconciled separately. */
	public static boolean recordPhysicalSale(MinecraftServer server, String cityId, long serial,
			long emeralds) {
		CityRecord city = CivilizationState.get(server).city(cityId);
		if (city == null || emeralds < 1) return false;
		EconomyModel model = model(server, city, EconomyState.get(server));
		int index = Math.toIntExact(serial - 1);
		if (!model.receivePlayerPayment(index, moneyOf(emeralds))) return false;
		EconomyState.get(server).put(city.id(), model);
		LAST_DISTRIBUTION.put(city.id(), model.distribution());
		return true;
	}

	/** Applies a completed player trade to the persistent model. */
	public static boolean trade(MinecraftServer server, String cityId, long serial,
			EconomyModel.Good good, boolean playerIsBuying, long emeralds) {
		CityRecord city = CivilizationState.get(server).city(cityId);
		if (city == null) return false;
		EconomyModel model = model(server, city, EconomyState.get(server));
		int index = Math.toIntExact(serial - 1);
		long lot = WarfrontRegistry.economy().tradeLot();
		boolean applied = playerIsBuying
				? model.playerBuy(index, good, lot, moneyOf(emeralds))
				: model.playerSell(index, good, lot, moneyOf(emeralds));
		if (!applied) return false;
		EconomyState.get(server).put(city.id(), model);
		LAST_DISTRIBUTION.put(city.id(), model.distribution());
		return true;
	}

	public static long actorStock(MinecraftServer server, String cityId, long serial, EconomyModel.Good good) {
		CityRecord city = CivilizationState.get(server).city(cityId);
		if (city == null) return 0L;
		return model(server, city, EconomyState.get(server)).actorStock(Math.toIntExact(serial - 1), good);
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
