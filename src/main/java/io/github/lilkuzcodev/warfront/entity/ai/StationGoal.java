package io.github.lilkuzcodev.warfront.entity.ai;

import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import io.github.lilkuzcodev.warfront.entity.StationManager;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The generic "man a station" behavior (architecture note 2). A station block declares a
 * need (one operator); soldiers claim it through {@link StationManager}, path to it, and
 * hold there facing outward from the base. Phase 4 turrets/AA/silos reuse this exact
 * claim-and-man system — only the station block registry grows.
 *
 * <p>Gated by the faction's tech level ("stations" unlock, level 1 by default).
 */
public class StationGoal extends Goal {
	private final SoldierEntity soldier;
	private BlockPos station;
	private int recheckCooldown;

	public StationGoal(SoldierEntity soldier) {
		this.soldier = soldier;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (soldier.getTarget() != null || soldier.isScattered() || soldier.getHomePos() == null) {
			return false;
		}
		if (!soldier.stationsUnlocked()) {
			return false;
		}
		if (--recheckCooldown > 0) {
			return false;
		}
		recheckCooldown = 100;
		station = StationManager.claimNearest(soldier);
		return station != null;
	}

	@Override
	public boolean canContinueToUse() {
		return station != null && soldier.getTarget() == null && !soldier.isScattered()
				&& soldier.stationsUnlocked() && StationManager.holds(soldier, station);
	}

	@Override
	public void start() {
		soldier.setStationPos(station);
		soldier.getNavigation().moveTo(station.getX() + 0.5, station.getY() + 1, station.getZ() + 0.5, 1.0);
	}

	@Override
	public void stop() {
		soldier.setStationPos(null);
		StationManager.release(soldier);
		station = null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		if (station == null) {
			return;
		}
		double distSq = soldier.distanceToSqr(station.getX() + 0.5, station.getY() + 1, station.getZ() + 0.5);
		if (distSq > 4.0) {
			if (soldier.getNavigation().isDone()) {
				soldier.getNavigation().moveTo(station.getX() + 0.5, station.getY() + 1, station.getZ() + 0.5, 1.0);
			}
			return;
		}
		soldier.getNavigation().stop();
		// face OUTWARD: away from the base center through the station
		BlockPos home = soldier.getHomePos();
		if (home != null) {
			double dx = station.getX() - home.getX();
			double dz = station.getZ() - home.getZ();
			soldier.getLookControl().setLookAt(
					station.getX() + 0.5 + dx * 4, soldier.getEyeY(), station.getZ() + 0.5 + dz * 4);
		}
	}
}
