package io.github.lilkuzcodev.warfront.entity.ai;

import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Inter-base roaming: walk the route to the destination base, then turn around and
 * shuttle back (v0.2.0 population overhaul). Doctrine flavor comes from who spawns the
 * squad and when (Vostok marching columns, Aegis small teams, Sarab night pairs) — the
 * movement itself is shared. Speed is doctrine-scaled so columns plod and teams move.
 */
public class TravelGoal extends Goal {
	private static final double ARRIVE_DISTANCE_SQR = 12 * 12;

	private final SoldierEntity soldier;

	public TravelGoal(SoldierEntity soldier) {
		this.soldier = soldier;
		setFlags(EnumSet.of(Goal.Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return soldier.isRoaming() && soldier.getTravelTo() != null && !soldier.isScattered()
				&& soldier.getTarget() == null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return false;
	}

	@Override
	public void tick() {
		BlockPos to = soldier.getTravelTo();
		if (to == null) {
			return;
		}
		if (soldier.blockPosition().distSqr(to) < ARRIVE_DISTANCE_SQR) {
			soldier.swapRoute();
			return;
		}
		if (soldier.getNavigation().isDone()) {
			// aggression maps 0.4..0.9 onto a modest walk-speed spread
			double speed = 0.75 + soldier.doctrine().aggression() * 0.25;
			soldier.getNavigation().moveTo(to.getX(), to.getY(), to.getZ(), speed);
		}
	}

	@Override
	public void stop() {
		soldier.getNavigation().stop();
	}
}
