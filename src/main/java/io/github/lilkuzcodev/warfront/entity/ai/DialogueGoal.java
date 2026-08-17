package io.github.lilkuzcodev.warfront.entity.ai;

import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.EnumSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

/** Holds a soldier in place and facing their conversation partner. */
public final class DialogueGoal extends Goal {
	private final SoldierEntity soldier;

	public DialogueGoal(SoldierEntity soldier) {
		this.soldier = soldier;
		setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return partner() != null && soldier.getTarget() == null;
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public void start() {
		soldier.getNavigation().stop();
	}

	@Override
	public void stop() {
		soldier.getNavigation().stop();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		ServerPlayer player = partner();
		if (player == null) {
			return;
		}
		soldier.getNavigation().stop();
		soldier.getLookControl().setLookAt(player, 30.0F, 30.0F);
	}

	private ServerPlayer partner() {
		return soldier.getDialoguePartner();
	}
}
