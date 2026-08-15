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
		CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
				Commands.literal("warfront").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("tech")
								.then(Commands.argument("faction", StringArgumentType.word())
										.executes(ctx -> viewTech(ctx.getSource(), StringArgumentType.getString(ctx, "faction")))
										.then(Commands.argument("level", IntegerArgumentType.integer(0, 4))
												.executes(ctx -> setTech(ctx.getSource(), StringArgumentType.getString(ctx, "faction"),
														IntegerArgumentType.getInteger(ctx, "level"))))))
						.then(Commands.literal("order")
								.then(Commands.argument("faction", StringArgumentType.word())
										.then(Commands.literal("assault")
												.then(Commands.argument("pos", BlockPosArgument.blockPos())
														.executes(ctx -> order(ctx.getSource(), StringArgumentType.getString(ctx, "faction"),
																BlockPosArgument.getBlockPos(ctx, "pos")))))))
						.then(Commands.literal("standing").executes(ctx -> standing(ctx.getSource())))
						.then(Commands.literal("bases").executes(ctx -> bases(ctx.getSource())))
						.then(Commands.literal("adopt").executes(ctx -> adoptDebug(ctx.getSource())))
						.then(Commands.literal("patrol")
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

	private static int patrol(CommandSourceStack source, String faction) {
		int spawned = WarfrontSystems.spawnRoamingSquad(source.getServer().overworld(), faction,
				BlockPos.containing(source.getPosition()));
		source.sendSuccess(() -> Component.literal("Spawned roaming squad of " + spawned + " for " + faction), true);
		return spawned;
	}

	private WarfrontCommands() {
	}
}
