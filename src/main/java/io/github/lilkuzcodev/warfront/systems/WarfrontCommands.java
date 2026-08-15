package io.github.lilkuzcodev.warfront.systems;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
						.then(Commands.literal("patrol").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("faction", StringArgumentType.word())
										.executes(ctx -> patrol(ctx.getSource(), StringArgumentType.getString(ctx, "faction")))))));
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
		if (state.bases().isEmpty()) {
			source.sendSuccess(() -> Component.literal("No bases discovered yet"), false);
			return 0;
		}
		state.bases().forEach((key, base) -> source.sendSuccess(() -> Component.literal(
				String.format("%s %s @ %d,%d,%d garrison=%d hydrated=%s", base.faction, base.tier,
						base.center.getX(), base.center.getY(), base.center.getZ(), base.garrison, base.hydrated)), false));
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
