package io.github.lilkuzcodev.warfront.systems;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.lilkuzcodev.warfront.civilization.CivilizationManager;
import io.github.lilkuzcodev.warfront.civilization.CivilizationMath;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState;
import io.github.lilkuzcodev.warfront.civilization.EconomyManager;
import io.github.lilkuzcodev.warfront.civilization.EconomyModel;
import io.github.lilkuzcodev.warfront.data.Faction;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import io.github.lilkuzcodev.warfront.order.General;
import io.github.lilkuzcodev.warfront.order.Order;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Debug commands:
 * /warfront tech <faction> [level]   — view or set a faction's tech level (testing)
 * /warfront order <faction> assault <pos>  — feed an Order through the full pipeline
 * /warfront standing                — view your standings with every faction
 * /warfront patrol <faction>        — force-spawn a roaming squad near you (testing)
 */
public final class WarfrontCommands {
	public static void init() {
		// root is player-accessible (standing/disposition/talk); admin branches gate themselves
		CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
				Commands.literal("warfront")
						.then(Commands.literal("disposition").executes(ctx -> disposition(ctx.getSource())))
						.then(Commands.literal("talk")
								.then(Commands.argument("option", StringArgumentType.word())
										.executes(ctx -> talk(ctx.getSource(), StringArgumentType.getString(ctx, "option")))))
						.then(Commands.literal("tech").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("faction", StringArgumentType.word())
										.executes(ctx -> viewTech(ctx.getSource(), StringArgumentType.getString(ctx, "faction")))
										.then(Commands.argument("level", IntegerArgumentType.integer(0, 4))
												.executes(ctx -> setTech(ctx.getSource(), StringArgumentType.getString(ctx, "faction"),
														IntegerArgumentType.getInteger(ctx, "level"))))))
						.then(Commands.literal("order").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("faction", StringArgumentType.word())
										.then(Commands.literal("assault")
												.then(Commands.argument("pos", BlockPosArgument.blockPos())
														.executes(ctx -> order(ctx.getSource(), StringArgumentType.getString(ctx, "faction"),
																BlockPosArgument.getBlockPos(ctx, "pos")))))))
						.then(Commands.literal("standing").executes(ctx -> standing(ctx.getSource())))
						.then(Commands.literal("bases").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> bases(ctx.getSource())))
						.then(Commands.literal("adopt").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> adoptDebug(ctx.getSource())))
						.then(Commands.literal("ledger").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("faction", StringArgumentType.word())
										.then(Commands.argument("event", StringArgumentType.word())
												.executes(ctx -> ledger(ctx.getSource(), StringArgumentType.getString(ctx, "faction"),
														StringArgumentType.getString(ctx, "event"))))))
						.then(Commands.literal("contract").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("faction", StringArgumentType.word())
										.then(Commands.argument("action", StringArgumentType.word())
												.executes(ctx -> contract(ctx.getSource(), StringArgumentType.getString(ctx, "faction"),
												StringArgumentType.getString(ctx, "action"))))))
						.then(Commands.literal("city")
								.then(Commands.literal("list").executes(ctx -> cityList(ctx.getSource())))
								.then(Commands.literal("inspect")
										.then(Commands.argument("city", StringArgumentType.word())
												.executes(ctx -> cityInspect(ctx.getSource(), StringArgumentType.getString(ctx, "city")))))
								.then(Commands.literal("economy")
										.then(Commands.argument("city", StringArgumentType.word())
												.executes(ctx -> cityEconomy(ctx.getSource(), StringArgumentType.getString(ctx, "city")))))
								.then(Commands.literal("shock").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
										.then(Commands.argument("city", StringArgumentType.word())
												.then(Commands.argument("type", StringArgumentType.word())
														.executes(ctx -> cityShock(ctx.getSource(),
																StringArgumentType.getString(ctx, "city"),
																StringArgumentType.getString(ctx, "type"))))))
								.then(Commands.literal("expeditions").executes(ctx -> cityExpeditions(ctx.getSource())))
								.then(Commands.literal("validate").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
										.executes(ctx -> cityValidate(ctx.getSource())))
								.then(Commands.literal("create").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
										.then(Commands.argument("city", StringArgumentType.word())
												.then(Commands.argument("faction", StringArgumentType.word())
														.then(Commands.argument("population", IntegerArgumentType.integer(1, 500))
																.executes(ctx -> cityCreate(ctx.getSource(),
																		StringArgumentType.getString(ctx, "city"),
																		StringArgumentType.getString(ctx, "faction"),
																		IntegerArgumentType.getInteger(ctx, "population"))))))))
						.then(Commands.literal("patrol").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("faction", StringArgumentType.word())
										.executes(ctx -> patrol(ctx.getSource(), StringArgumentType.getString(ctx, "faction")))))));
	}

	private static int cityCreate(CommandSourceStack source, String city, String faction, int population) {
		if (WarfrontRegistry.faction(faction) == null) {
			source.sendFailure(Component.literal("Unknown faction: " + faction));
			return 0;
		}
		if (source.getLevel() != source.getServer().overworld()) {
			source.sendFailure(Component.literal("Phase 1 cities currently live in the overworld"));
			return 0;
		}
		try {
			var created = CivilizationManager.createCity(source.getLevel(), city, faction,
					BlockPos.containing(source.getPosition()), population);
			source.sendSuccess(() -> Component.literal("Created " + created.id() + " for " + faction + " with "
					+ created.citizens().size() + " citizens; approach/leave to drive the fidelity ladder"), true);
			return created.citizens().size();
		} catch (IllegalArgumentException exception) {
			source.sendFailure(Component.literal(exception.getMessage()));
			return 0;
		}
	}

	private static int cityList(CommandSourceStack source) {
		var state = CivilizationState.get(source.getServer());
		if (state.cities().isEmpty()) {
			source.sendSuccess(() -> Component.literal("No cities"), false);
			return 0;
		}
		for (var city : state.cities().values()) {
			source.sendSuccess(() -> Component.literal(city.id() + " [" + city.faction() + "] @ "
					+ city.center().toShortString() + " citizens=" + city.citizens().size()
					+ " soldiers=" + state.assignedSoldierCount(city.id())), false);
		}
		return state.cities().size();
	}

	private static int cityInspect(CommandSourceStack source, String cityId) {
		var state = CivilizationState.get(source.getServer());
		var city = state.city(CivilizationManager.normalizeId(cityId));
		if (city == null) {
			source.sendFailure(Component.literal("Unknown city: " + cityId));
			return 0;
		}
		long embodied = city.citizens().values().stream().filter(c -> c.tier().id().equals("embodied")).count();
		long local = city.citizens().values().stream().filter(c -> c.tier().id().equals("local")).count();
		long virtual = city.citizens().size() - embodied - local;
		long nowTick = source.getServer().overworld().getGameTime();
		long away = city.citizens().values().stream().filter(c -> c.isAway(nowTick)).count();
		long goods = EconomyManager.distribution(source.getServer(), city).totalGoods();
		long tickNanos = CivilizationManager.lastCityTickNanos(city.id());
		source.sendSuccess(() -> Component.literal(String.format(
				"%s: embodied=%d local=%d virtual=%d away=%d citizens=%d/%d housing goods=%d soldiers=%d lastTick=%.3fms",
				city.id(), embodied, local, virtual, away, city.citizens().size(), city.housing(), goods,
				state.assignedSoldierCount(city.id()), tickNanos < 0 ? -1.0 : tickNanos / 1_000_000.0)), false);
		return city.citizens().size();
	}

	private static int cityExpeditions(CommandSourceStack source) {
		var state = CivilizationState.get(source.getServer());
		var expeditions = state.expeditions();
		if (expeditions.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No parties are out."), false);
			return 0;
		}
		long now = source.getServer().overworld().getGameTime();
		for (var expedition : expeditions.values()) {
			long remaining = Math.max(0L, expedition.returnTick() - now);
			source.sendSuccess(() -> Component.literal(String.format("%s: %d on %s%s — back in %ds",
					expedition.cityId(), expedition.party(),
					expedition.kind().toLowerCase(java.util.Locale.ROOT),
					expedition.targetCityId().isEmpty() ? "" : " vs " + expedition.targetCityId(),
					remaining / 20)), false);
		}
		return expeditions.size();
	}

	private static int cityValidate(CommandSourceStack source) {
		try {
			var result = CivilizationManager.validatePhaseOne();
			source.sendSuccess(() -> Component.literal(String.format(
					"Phase 1 PASS: transition goods %d->%d; virtual elapsed produced=%d remainder=%d; deterministic=%s",
					result.goodsBeforeTransitions(), result.goodsAfterTransitions(), result.virtualGoodsProduced(),
					result.workRemainder(), result.deterministicReplay())), true);
			return 1;
		} catch (RuntimeException exception) {
			source.sendFailure(Component.literal("Phase 1 FAIL: " + exception.getMessage()));
			return 0;
		}
	}

	private static int cityEconomy(CommandSourceStack source, String cityId) {
		var city = CivilizationState.get(source.getServer()).city(CivilizationManager.normalizeId(cityId));
		if (city == null) {
			source.sendFailure(Component.literal("Unknown city: " + cityId));
			return 0;
		}
		var d = EconomyManager.distribution(source.getServer(), city);
		var audit = EconomyManager.conservation(source.getServer(), city);
		double ms = EconomyManager.tickNanos(city.id()) / 1_000_000.0;
		source.sendSuccess(() -> Component.literal(String.format(
				"%s economy tick=%d Gini=%.4f poor=%.1f%% top5=%.1f%% wealth[min/q25/med/q75/p90/max]=%d/%d/%d/%d/%d/%d",
				city.id(), d.tick(), d.gini(), d.poorShare() * 100, d.topFiveShare() * 100,
				d.minimum(), d.lowerQuartile(), d.median(), d.upperQuartile(), d.p90(), d.maximum())), false);
		source.sendSuccess(() -> Component.literal(String.format(
				"prices food=%d ore=%d timber=%d crafts=%d; money=%d; goods=%d; conserved=%s; lastTick=%.3fms",
				EconomyManager.price(source.getServer(), city, EconomyModel.Good.FOOD),
				EconomyManager.price(source.getServer(), city, EconomyModel.Good.ORE),
				EconomyManager.price(source.getServer(), city, EconomyModel.Good.TIMBER),
				EconomyManager.price(source.getServer(), city, EconomyModel.Good.CRAFTS),
				d.totalMoney(), d.totalGoods(), audit.balanced(), ms)), false);
		source.sendSuccess(() -> Component.literal(String.format(
				"emeralds: held=%d treasury=%d; %d/lot food=%d ore=%d timber=%d crafts=%d (buy); "
						+ "carried in=%d out=%d (expeditions + player trade)",
				EconomyManager.emeraldsOf(d.totalMoney()),
				EconomyManager.emeraldsOf(EconomyManager.treasury(source.getServer(), city)),
				WarfrontRegistry.economy().tradeLot(),
				EconomyManager.lotPriceEmeralds(source.getServer(), city, EconomyModel.Good.FOOD, true),
				EconomyManager.lotPriceEmeralds(source.getServer(), city, EconomyModel.Good.ORE, true),
				EconomyManager.lotPriceEmeralds(source.getServer(), city, EconomyModel.Good.TIMBER, true),
				EconomyManager.lotPriceEmeralds(source.getServer(), city, EconomyModel.Good.CRAFTS, true),
				EconomyManager.emeraldsOf(audit.externalMoneyIn()),
				EconomyManager.emeraldsOf(audit.externalMoneyOut()))), false);
		return 1;
	}

	private static int cityShock(CommandSourceStack source, String cityId, String type) {
		var city = CivilizationState.get(source.getServer()).city(CivilizationManager.normalizeId(cityId));
		if (city == null) {
			source.sendFailure(Component.literal("Unknown city: " + cityId));
			return 0;
		}
		try {
			EconomyModel.Shock shock = EconomyModel.Shock.valueOf(type.toUpperCase(java.util.Locale.ROOT));
			EconomyManager.injectShock(source.getServer(), city, shock);
			source.sendSuccess(() -> Component.literal("Injected " + shock.name().toLowerCase(java.util.Locale.ROOT)
					+ " into " + city.id()), true);
			return 1;
		} catch (IllegalArgumentException exception) {
			source.sendFailure(Component.literal("Shock must be vein_depletion, blight, raid, or fire"));
			return 0;
		}
	}

	private static int viewTech(CommandSourceStack source, String faction) {
		WarfrontState state = WarfrontState.get(source.getServer());
		double points = state.getPoints(faction);
		int level = state.techLevel(faction);
		source.sendSuccess(() -> Component.literal(String.format("%s: tech level %d (%.1f points; thresholds %s)",
				faction, level, points, WarfrontRegistry.tech().levelThresholds())), false);
		return level;
	}

	private static int setTech(CommandSourceStack source, String faction, int level) {
		if (WarfrontRegistry.faction(faction) == null) {
			source.sendFailure(Component.literal("Unknown faction: " + faction));
			return 0;
		}
		WarfrontState.get(source.getServer()).setPointsForLevel(faction, level);
		// refresh loadouts of loaded soldiers so the change is immediately visible
		ServerLevel overworld = source.getServer().overworld();
		List<SoldierEntity> loaded = overworld.getEntitiesOfClass(SoldierEntity.class,
				new AABB(-3.0E7, -512, -3.0E7, 3.0E7, 512, 3.0E7), s -> faction.equals(s.getFaction()));
		loaded.forEach(s -> s.applyLoadout(level));
		source.sendSuccess(() -> Component.literal("Set " + faction + " to tech level " + level
				+ "; refreshed gear on " + loaded.size() + " loaded soldier(s)"), true);
		return 1;
	}

	private static int order(CommandSourceStack source, String faction, BlockPos pos) {
		Order order = new Order("debug", faction, "assault", pos, List.of(), 0, 1, List.of());
		General.submit(source.getServer(), order, msg -> source.sendSuccess(() -> Component.literal(msg), false));
		return 1;
	}

	private static int standing(CommandSourceStack source) {
		if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
			WarfrontState state = WarfrontState.get(source.getServer());
			for (Faction faction : WarfrontRegistry.factions().values()) {
				float value = state.standing(player.getUUID(), faction.id());
				source.sendSuccess(() -> Component.literal(String.format("%s: %.1f (%s)", faction.name(), value,
						WarfrontRegistry.standing().label(value))), false);
			}
			return 1;
		}
		source.sendFailure(Component.literal("Run as a player"));
		return 0;
	}

	private static int bases(CommandSourceStack source) {
		var state = WarfrontState.get(source.getServer());
		ServerLevel level = source.getServer().overworld();
		if (state.bases().isEmpty()) {
			source.sendSuccess(() -> Component.literal("No bases discovered yet"), false);
			return 0;
		}
		state.bases().forEach((key, base) -> {
			Faction faction = WarfrontRegistry.faction(base.faction);
			int target = faction == null ? -1 : faction.population().garrisonTarget(base.tier, key.hashCode());
			int loaded = BaseManager.loadedGarrisonCount(level, key);
			source.sendSuccess(() -> Component.literal(
					String.format("%s %s @ %d,%d,%d stored=%d loaded=%d target=%d hydrated=%s",
							base.faction, base.tier, base.center.getX(), base.center.getY(), base.center.getZ(),
							base.garrison, loaded, target, base.hydrated)), false);
		});
		return state.bases().size();
	}

	/** Diagnostics for base discovery: what structure references exist at my position? */
	private static int adoptDebug(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		BlockPos pos = BlockPos.containing(source.getPosition());
		var start = level.structureManager().getStructureWithPieceAt(pos, BaseManager.ALL_BASES);
		source.sendSuccess(() -> Component.literal("withPieceAt(#warfront:bases): valid=" + start.isValid()
				+ (start.isValid() ? " box=" + start.getBoundingBox() : "")), false);
		var all = level.structureManager().getAllStructuresAt(pos);
		var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
		for (var entry : all.entrySet()) {
			source.sendSuccess(() -> Component.literal("ref: " + registry.getKey(entry.getKey())
					+ " x" + entry.getValue().size()), false);
		}
		if (all.isEmpty()) {
			source.sendSuccess(() -> Component.literal("no structure references at " + pos.toShortString()), false);
		}
		return 1;
	}

	/** Test/debug: inject a ledger event for the executing player (drives bias scenarios). */
	private static int ledger(CommandSourceStack source, String faction, String event) {
		if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
			source.sendFailure(Component.literal("Run as a player"));
			return 0;
		}
		WarfrontState state = WarfrontState.get(source.getServer());
		float applied = state.recordEvent(player.getUUID(), faction, event, WarfrontState.clock(source.getLevel()));
		source.sendSuccess(() -> Component.literal(String.format("Recorded %s -> %s (weight %.1f)", event, faction, applied)), true);
		return 1;
	}

	/** Test/debug: drive the work-order lifecycle without the dialogue UI. */
	private static int contract(CommandSourceStack source, String faction, String action) {
		if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
			source.sendFailure(Component.literal("Run as a player"));
			return 0;
		}
		WarfrontState state = WarfrontState.get(source.getServer());
		switch (action) {
			case "offer" -> {
				var order = io.github.lilkuzcodev.warfront.dialogue.WorkOrders.offer(player, faction, false);
				source.sendSuccess(() -> Component.literal(order == null ? "no order offered (one active already?)"
						: "offered " + order.id()), true);
			}
			case "penance" -> {
				var order = io.github.lilkuzcodev.warfront.dialogue.WorkOrders.offer(player, faction, true);
				source.sendSuccess(() -> Component.literal(order == null ? "no penance order offered"
						: "offered " + order.id()), true);
			}
			case "accept" -> {
				io.github.lilkuzcodev.warfront.dialogue.WorkOrders.accept(player, faction);
				source.sendSuccess(() -> Component.literal("accepted"), true);
			}
			case "turnin" -> {
				boolean ok = io.github.lilkuzcodev.warfront.dialogue.WorkOrders.turnIn(player, faction);
				source.sendSuccess(() -> Component.literal(ok ? "turned in" : "not complete"), true);
			}
			case "abandon" -> {
				io.github.lilkuzcodev.warfront.dialogue.WorkOrders.abandon(player, faction);
				source.sendSuccess(() -> Component.literal("abandoned"), true);
			}
			default -> {
				var contract = state.contract(player.getUUID(), faction);
				source.sendSuccess(() -> Component.literal(contract == null ? "no contract"
						: String.format("%s %s target=%s item=%s %d/%d state=%s penance=%s", contract.orderId(),
								contract.type(), contract.targetFaction(), contract.item(), contract.progress(),
								contract.count(), contract.state(), contract.penance())), false);
			}
		}
		return 1;
	}

	/** Player-facing: numeric disposition + band per faction (verification aid too). */
	private static int disposition(CommandSourceStack source) {
		if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
			source.sendFailure(Component.literal("Run as a player"));
			return 0;
		}
		WarfrontState state = WarfrontState.get(source.getServer());
		long now = WarfrontState.clock(source.getLevel());
		for (Faction faction : WarfrontRegistry.factions().values()) {
			float score = state.disposition(player.getUUID(), faction.id(), now);
			String band = state.dispositionBand(player.getUUID(), faction.id(), now);
			var events = state.ledgerEvents(player.getUUID(), faction.id());
			source.sendSuccess(() -> Component.literal(String.format("%s: %.1f (%s), %d remembered events",
					faction.name(), score, band, events.size())), false);
		}
		return 1;
	}

	/** Chat-fallback dialogue choice (clickable components run this). */
	private static int talk(CommandSourceStack source, String option) {
		if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
			io.github.lilkuzcodev.warfront.dialogue.DialogueSessions.choose(player, option);
			return 1;
		}
		return 0;
	}

	private static int patrol(CommandSourceStack source, String faction) {
		int spawned = WarfrontSystems.spawnRoamingSquad(source.getServer().overworld(), faction,
				BlockPos.containing(source.getPosition()));
		source.sendSuccess(() -> Component.literal("Spawned roaming squad of " + spawned + " for " + faction), true);
		return spawned;
	}

	private WarfrontCommands() {
	}
}
