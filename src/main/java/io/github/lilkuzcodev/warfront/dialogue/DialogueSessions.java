package io.github.lilkuzcodev.warfront.dialogue;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Active dialogue sessions (Stage 4B). The server drives everything: opens the screen,
 * selects options, resolves response lines, applies effects, and hard-ends the
 * conversation the moment combat starts or the parties separate.
 */
public final class DialogueSessions {
	private static final double MAX_TALK_DISTANCE_SQR = 8 * 8;

	private static class Session {
		final UUID soldierUuid;
		final int soldierEntityId;
		final String faction;
		final Set<String> shownThisConvo = new HashSet<>();
		final List<DialogueOption> offered = new ArrayList<>();
		boolean moreUsed;

		Session(SoldierEntity soldier) {
			this.soldierUuid = soldier.getUUID();
			this.soldierEntityId = soldier.getId();
			this.faction = soldier.getFaction();
		}
	}

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	public static void init() {
		// combat/distance watchdog: conversations end automatically
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 != 0 || SESSIONS.isEmpty()) {
				return;
			}
			for (UUID playerId : List.copyOf(SESSIONS.keySet())) {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				SoldierEntity soldier = player == null ? null : soldierOf(player);
				if (player == null || soldier == null || !soldier.isAlive()
						|| soldier.getTarget() != null
						|| player.distanceToSqr(soldier) > MAX_TALK_DISTANCE_SQR
						|| WarfrontState.get(server).isHostileTo(playerId, SESSIONS.get(playerId).faction)) {
					close(player, playerId);
				}
			}
		});
		// recon contract credit rides the same cadence class of sweeps
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 == 0) {
				WorkOrders.tickRecon(server.overworld());
			}
		});
	}

	private static SoldierEntity soldierOf(ServerPlayer player) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null) {
			return null;
		}
		var entity = ((ServerLevel) player.level()).getEntity(session.soldierUuid);
		return entity instanceof SoldierEntity soldier ? soldier : null;
	}

	/** Right-click entry point (non-combat). */
	public static void open(ServerPlayer player, SoldierEntity soldier) {
		Session session = new Session(soldier);
		SESSIONS.put(player.getUUID(), session);
		send(player, soldier, session, "greet", true);
	}

	public static void onSoldierGone(SoldierEntity soldier) {
		SESSIONS.entrySet().removeIf(entry -> entry.getValue().soldierUuid.equals(soldier.getUUID()));
	}

	/** Handles a client choice: specials (__more/__leave) or an offered option id. */
	public static void choose(ServerPlayer player, String optionId) {
		Session session = SESSIONS.get(player.getUUID());
		SoldierEntity soldier = soldierOf(player);
		if (session == null || soldier == null) {
			return;
		}
		WarfrontState state = WarfrontState.get(player.level().getServer());
		long now = player.level().getGameTime();
		if ("__leave".equals(optionId)) {
			close(player, player.getUUID());
			return;
		}
		if ("__more".equals(optionId)) {
			if (!session.moreUsed) {
				session.moreUsed = true;
				// reroll: previously offered non-exit options are excluded via shownThisConvo
				send(player, soldier, session, null, false);
			}
			return;
		}
		DialogueOption option = session.offered.stream().filter(o -> o.id().equals(optionId)).findFirst().orElse(null);
		if (option == null) {
			return;
		}
		state.markUsed(player.getUUID(), option.id(), now);
		boolean ended = applyEffects(player, soldier, session, option);
		if (ended) {
			return;
		}
		send(player, soldier, session, option.responseClass(), false);
	}

	/** Runs an option's effects. Returns true if the session was ended by an effect. */
	private static boolean applyEffects(ServerPlayer player, SoldierEntity soldier, Session session,
			DialogueOption option) {
		WarfrontState state = WarfrontState.get(player.level().getServer());
		long now = player.level().getGameTime();
		String faction = session.faction;
		for (DialogueOption.Effect effect : option.effects()) {
			switch (effect.type()) {
				case "standing" -> state.addStanding(player.getUUID(), faction, effect.amount());
				case "disposition" -> state.recordEvent(player.getUUID(), faction, effect.arg(), now);
				case "take_items" -> WorkOrders.consumeItems(player, effect.item(), effect.amount());
				case "give_items" -> {
					var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(effect.item()));
					player.getInventory().placeItemBackInInventory(new ItemStack(item, Math.max(1, effect.amount())));
				}
				case "open_trade" -> {
					close(player, player.getUUID());
					soldier.openQuartermaster(player);
					return true;
				}
				case "offer_order" -> {
					var order = WorkOrders.offer(player, faction, "penance".equals(effect.arg()));
					if (order != null) {
						player.sendSystemMessage(Component.translatable("dialogue.warfront.order_offer_" + order.type(),
								order.count(), orderSubject(order)));
					}
				}
				case "accept_order" -> WorkOrders.accept(player, faction);
				case "decline_order" -> WorkOrders.decline(player, faction);
				case "turn_in_order" -> WorkOrders.turnIn(player, faction);
				case "abandon_order" -> WorkOrders.abandon(player, faction);
				case "intel" -> sendIntel(player, soldier, effect.arg());
				case "provoke" -> {
					state.recordEvent(player.getUUID(), faction, "insulted", now);
					state.addStanding(player.getUUID(), faction, -8);
					close(player, player.getUUID());
					soldier.setTarget(player);
					if ("squad".equals(effect.arg()) && soldier.getSquadId() != null) {
						io.github.lilkuzcodev.warfront.entity.SquadManager.alertSquad(soldier.getSquadId(),
								(ServerLevel) player.level(), player);
					}
					return true;
				}
				case "end" -> {
					close(player, player.getUUID());
					return true;
				}
				default -> Warfront.LOGGER.warn("Unknown dialogue effect type {}", effect.type());
			}
		}
		return false;
	}

	private static String orderSubject(DialogueRegistry.WorkOrder order) {
		if (!order.targetFaction().isEmpty()) {
			var faction = WarfrontRegistry.faction(order.targetFaction());
			return faction == null ? order.targetFaction() : faction.name();
		}
		return order.item().replace("minecraft:", "").replace('_', ' ');
	}

	private static void sendIntel(ServerPlayer player, SoldierEntity soldier, String kind) {
		ServerLevel level = (ServerLevel) player.level();
		switch (kind) {
			case "nearest_base" -> {
				BlockPos base = WorkOrders.nearestBase(level, soldier.getFaction(), player.blockPosition());
				player.sendSystemMessage(base == null
						? Component.translatable("dialogue.warfront.intel_no_base")
						: Component.translatable("dialogue.warfront.intel_nearest_base",
								base.getX(), base.getZ(), direction(player.blockPosition(), base)));
			}
			case "rival_base" -> {
				for (var other : WarfrontRegistry.factions().values()) {
					if (!other.id().equals(soldier.getFaction())
							&& "hostile".equals(WarfrontRegistry.relation(soldier.getFaction(), other.id()))) {
						BlockPos base = WorkOrders.nearestBase(level, other.id(), player.blockPosition());
						if (base != null) {
							player.sendSystemMessage(Component.translatable("dialogue.warfront.intel_rival_base",
									other.name(), base.getX(), base.getZ()));
							return;
						}
					}
				}
				player.sendSystemMessage(Component.translatable("dialogue.warfront.intel_no_base"));
			}
			default -> player.sendSystemMessage(Component.translatable("dialogue.warfront.intel_patrol_hint"));
		}
	}

	private static String direction(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dz = to.getZ() - from.getZ();
		String ns = dz < 0 ? "north" : "south";
		String ew = dx < 0 ? "west" : "east";
		return Math.abs(dz) > Math.abs(dx) * 2 ? ns : Math.abs(dx) > Math.abs(dz) * 2 ? ew : ns + ew;
	}

	/** Selects options and pushes the screen state (open or refresh) to the client. */
	private static void send(ServerPlayer player, SoldierEntity soldier, Session session,
			String responseClass, boolean openScreen) {
		WarfrontState state = WarfrontState.get(player.level().getServer());
		long now = player.level().getGameTime();
		DialogueEngine.Context ctx = DialogueEngine.Context.of(player, soldier);
		List<DialogueOption> picked = DialogueEngine.select(ctx, state, session.shownThisConvo, now);
		session.offered.clear();
		session.offered.addAll(picked);
		List<String> ids = picked.stream().map(DialogueOption::id).toList();
		session.shownThisConvo.addAll(ids);
		state.markShown(player.getUUID(), ids, DialogueEngine.SHOWN_HISTORY_CAP);

		String line = "dialogue.warfront.resp.silent";
		List<String> lines = responseClass == null ? List.of()
				: DialogueRegistry.responseLines(responseClass, session.faction, ctx.band());
		if (!lines.isEmpty()) {
			line = lines.get(player.level().getRandom().nextInt(lines.size()));
		}
		var faction = WarfrontRegistry.faction(session.faction);
		ServerPlayNetworking.send(player, new WarfrontNet.DialogueS2C(
				session.soldierEntityId,
				DialogueRegistry.soldierName(session.faction, session.soldierUuid),
				soldier.getRank(), session.faction, faction == null ? session.faction : faction.name(),
				ctx.standing(), state.standing(player.getUUID(), session.faction), ctx.band(), line,
				picked.stream().map(o -> new WarfrontNet.OptionEntry(o.id(), o.textKey())).toList(),
				!session.moreUsed, openScreen));
	}

	private static void close(ServerPlayer player, UUID playerId) {
		SESSIONS.remove(playerId);
		if (player != null) {
			ServerPlayNetworking.send(player, new WarfrontNet.DialogueCloseS2C(0));
		}
	}

	private DialogueSessions() {
	}
}
