package io.github.lilkuzcodev.warfront.dialogue;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Work-order lifecycle (Stage 4D): offer → accept → progress → turn-in, plus abandon
 * penalties. Pools are JSON per faction; penance variants (smaller rewards, larger
 * disposition gain) surface only for negative-band players clawing back.
 */
public final class WorkOrders {

	/** Offers a random eligible order and stores it as "offered". Returns it, or null. */
	public static DialogueRegistry.WorkOrder offer(ServerPlayer player, String faction, boolean penanceOnly) {
		WarfrontState state = WarfrontState.get(player.level().getServer());
		if (state.contract(player.getUUID(), faction) != null) {
			return null; // one contract per faction at a time
		}
		List<DialogueRegistry.WorkOrder> pool = new ArrayList<>();
		for (DialogueRegistry.WorkOrder order : DialogueRegistry.workOrders(faction)) {
			if (order.penance() == penanceOnly) {
				pool.add(order);
			}
		}
		if (pool.isEmpty()) {
			return null;
		}
		DialogueRegistry.WorkOrder order = pool.get(player.level().getRandom().nextInt(pool.size()));
		state.putContract(player.getUUID(), faction, new WarfrontState.Contract(
				order.id(), order.type(), order.targetFaction(), order.item(), order.count(), 0, "offered",
				order.penance()));
		return order;
	}

	public static void accept(ServerPlayer player, String faction) {
		WarfrontState state = WarfrontState.get(player.level().getServer());
		WarfrontState.Contract contract = state.contract(player.getUUID(), faction);
		if (contract != null && "offered".equals(contract.state())) {
			state.putContract(player.getUUID(), faction, new WarfrontState.Contract(contract.orderId(),
					contract.type(), contract.targetFaction(), contract.item(), contract.count(), 0, "active",
					contract.penance()));
		}
	}

	public static void decline(ServerPlayer player, String faction) {
		WarfrontState state = WarfrontState.get(player.level().getServer());
		WarfrontState.Contract contract = state.contract(player.getUUID(), faction);
		if (contract != null && "offered".equals(contract.state())) {
			state.clearContract(player.getUUID(), faction);
		}
	}

	public static boolean isComplete(WarfrontState state, ServerPlayer player, WarfrontState.Contract contract) {
		if (!"active".equals(contract.state())) {
			return false;
		}
		return switch (contract.type()) {
			case "eliminate", "recon" -> contract.progress() >= contract.count();
			case "supply" -> DialogueEngine.countItems(player, contract.item()) >= contract.count();
			default -> false;
		};
	}

	/** Kill-credit hook (called from SoldierEntity.die). */
	public static void onSoldierKilled(ServerPlayer player, SoldierEntity soldier) {
		WarfrontState state = WarfrontState.get(player.level().getServer());
		for (var entry : state.contractsOf(player.getUUID()).entrySet()) {
			WarfrontState.Contract contract = entry.getValue();
			if ("active".equals(contract.state()) && "eliminate".equals(contract.type())
					&& soldier.getFaction().equals(contract.targetFaction())
					&& contract.progress() < contract.count()) {
				state.putContract(player.getUUID(), entry.getKey(), new WarfrontState.Contract(
						contract.orderId(), contract.type(), contract.targetFaction(), contract.item(),
						contract.count(), contract.progress() + 1, contract.state(), contract.penance()));
			}
		}
	}

	/** Recon-credit sweep: players near a target faction's base get their visit counted. */
	public static void tickRecon(ServerLevel level) {
		WarfrontState state = WarfrontState.get(level.getServer());
		for (ServerPlayer player : level.players()) {
			for (var entry : state.contractsOf(player.getUUID()).entrySet()) {
				WarfrontState.Contract contract = entry.getValue();
				if (!"active".equals(contract.state()) || !"recon".equals(contract.type())
						|| contract.progress() >= contract.count()) {
					continue;
				}
				for (var baseEntry : state.bases().entrySet()) {
					WarfrontState.Base base = baseEntry.getValue();
					if (base.faction.equals(contract.targetFaction())
							&& player.blockPosition().closerThan(base.center, 48)) {
						state.putContract(player.getUUID(), entry.getKey(), new WarfrontState.Contract(
								contract.orderId(), contract.type(), contract.targetFaction(), contract.item(),
								contract.count(), contract.count(), contract.state(), contract.penance()));
						player.sendSystemMessage(Component.translatable("dialogue.warfront.recon_complete"));
						break;
					}
				}
			}
		}
	}

	/** Turn-in: consumes supplies, pays standing + disposition, applies matrix consequence. */
	public static boolean turnIn(ServerPlayer player, String faction) {
		WarfrontState state = WarfrontState.get(player.level().getServer());
		WarfrontState.Contract contract = state.contract(player.getUUID(), faction);
		if (contract == null || !isComplete(state, player, contract)) {
			return false;
		}
		if ("supply".equals(contract.type())) {
			consumeItems(player, contract.item(), contract.count());
		}
		DialogueRegistry.WorkOrder order = DialogueRegistry.workOrder(faction, contract.orderId());
		int reward = order == null ? 6 : order.rewardStanding();
		long now = WarfrontState.clock(player.level());
		state.addStanding(player.getUUID(), faction, reward);
		state.recordEvent(player.getUUID(), faction,
				contract.penance() ? "penance_completed" : "contract_completed", now);
		if (!contract.targetFaction().isEmpty() && !contract.targetFaction().equals(faction)) {
			// the matrix consequence: the targeted faction remembers, sight unseen
			float weight = WarfrontRegistry.disposition().events()
					.getOrDefault("contract_target", new io.github.lilkuzcodev.warfront.data.DispositionConfig.EventDef(-10, 3)).weight();
			state.addLedgerEvent(player.getUUID(), contract.targetFaction(), "contract_target", now, weight);
			state.addStanding(player.getUUID(), contract.targetFaction(), -Math.abs(reward) / 2.0F);
		}
		state.clearContract(player.getUUID(), faction);
		Warfront.LOGGER.info("{} completed {} order {} for {}", player.getName().getString(), contract.type(),
				contract.orderId(), faction);
		return true;
	}

	/** Abandoning costs standing and lands a contract_failed memory. */
	public static void abandon(ServerPlayer player, String faction) {
		WarfrontState state = WarfrontState.get(player.level().getServer());
		WarfrontState.Contract contract = state.contract(player.getUUID(), faction);
		if (contract == null) {
			return;
		}
		state.clearContract(player.getUUID(), faction);
		state.addStanding(player.getUUID(), faction, -5);
		state.recordEvent(player.getUUID(), faction, "contract_failed", WarfrontState.clock(player.level()));
	}

	static void consumeItems(ServerPlayer player, String itemId, int count) {
		int remaining = count;
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
			var stack = inventory.getItem(i);
			if (!stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
					.toString().equals(itemId)) {
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				remaining -= take;
			}
		}
	}

	/** Nearest base of the given faction, for recon/intel directions. */
	public static BlockPos nearestBase(ServerLevel level, String faction, BlockPos from) {
		WarfrontState state = WarfrontState.get(level.getServer());
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (WarfrontState.Base base : state.bases().values()) {
			if (base.faction.equals(faction)) {
				double dist = from.distSqr(base.center);
				if (dist < bestDist) {
					bestDist = dist;
					best = base.center;
				}
			}
		}
		return best;
	}

	private WorkOrders() {
	}
}
