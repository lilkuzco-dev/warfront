package io.github.lilkuzcodev.warfront.entity.ai;

import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Keeps a king at his seat. The soldierly movement goals are removed from kings
 * entirely (see SoldierEntity#applyRoyalIdentity), so this goal's only job is to walk
 * him back when combat knockback, players or pathing drift displace him from the spot
 * he was placed at — his court. The court position is the first position the king ever
 * stands at, which for a baked-in king is where the importer placed him.
 */
public class KingCourtGoal extends Goal {

	/** How far the king may drift before he walks back. */
	private static final double COURT_RADIUS = 5.0;

	private final SoldierEntity king;

	public KingCourtGoal(SoldierEntity king) {
		this.king = king;
		setFlags(EnumSet.of(Goal.Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		if (king.getDialoguePartner() != null) {
			return false;
		}
		BlockPos court = king.getHomePos();
		if (court == null) {
			king.setHomePos(king.blockPosition());
			return false;
		}
		return !court.closerToCenterThan(king.position(), COURT_RADIUS);
	}

	@Override
	public boolean canContinueToUse() {
		BlockPos court = king.getHomePos();
		return court != null && !court.closerToCenterThan(king.position(), 1.5)
				&& !king.getNavigation().isDone();
	}

	@Override
	public void start() {
		BlockPos court = king.getHomePos();
		if (court != null) {
			king.getNavigation().moveTo(court.getX() + 0.5, court.getY(), court.getZ() + 0.5, 0.6);
		}
	}

	@Override
	public void stop() {
		king.getNavigation().stop();
	}
}
