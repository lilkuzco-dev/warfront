package io.github.lilkuzcodev.warfront.entity.ai;

import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Perimeter patrol around the soldier's home position: walks successive waypoints on a
 * ring, pillager-patrol style. Roaming squads get a larger ring via {@link #ROAM_RADIUS}.
 */
public class PatrolGoal extends Goal {
	private static final double BASE_RADIUS = 12.0;
	public static final double ROAM_RADIUS = 40.0;

	private final SoldierEntity soldier;
	private double angle;
	private BlockPos waypoint;
	private int stuckTicks;

	public PatrolGoal(SoldierEntity soldier) {
		this.soldier = soldier;
		this.angle = soldier.getRandom().nextDouble() * Math.PI * 2;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return soldier.getHomePos() != null
				&& soldier.getTarget() == null
				&& soldier.getStationPos() == null
				&& !soldier.isScattered()
				&& soldier.getRandom().nextInt(reducedTickDelay(60)) == 0;
	}

	@Override
	public boolean canContinueToUse() {
		return waypoint != null && soldier.getTarget() == null && stuckTicks < 140
				&& !waypoint.closerToCenterThan(soldier.position(), 2.5);
	}

	@Override
	public void start() {
		nextWaypoint();
	}

	@Override
	public void stop() {
		waypoint = null;
		stuckTicks = 0;
	}

	@Override
	public void tick() {
		stuckTicks++;
		if (soldier.getNavigation().isDone() && waypoint != null) {
			soldier.getNavigation().moveTo(waypoint.getX(), waypoint.getY(), waypoint.getZ(), 0.7);
		}
	}

	private void nextWaypoint() {
		BlockPos home = soldier.getHomePos();
		if (home == null) {
			return;
		}
		boolean roaming = "roaming".equals(soldier.getRank()) || soldier.getSquadId() != null && soldier.getHomePos() != null
				&& !soldier.blockPosition().closerThan(home, 64);
		double radius = (roaming ? ROAM_RADIUS : BASE_RADIUS) * (0.75 + soldier.getRandom().nextDouble() * 0.5);
		angle += Math.PI / 3 + soldier.getRandom().nextDouble() * 0.5;
		int x = home.getX() + Mth.floor(Math.cos(angle) * radius);
		int z = home.getZ() + Mth.floor(Math.sin(angle) * radius);
		waypoint = new BlockPos(x, home.getY(), z);
		soldier.getNavigation().moveTo(x, waypoint.getY(), z, 0.7);
	}
}
