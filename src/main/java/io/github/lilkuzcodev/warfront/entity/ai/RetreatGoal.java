package io.github.lilkuzcodev.warfront.entity.ai;

import io.github.lilkuzcodev.warfront.data.Doctrine;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import io.github.lilkuzcodev.warfront.entity.SquadManager;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Doctrine-driven withdrawal. The squad's loss fraction is compared against the
 * faction's retreat threshold: Vostok (0.7) presses until deep losses, Aegis (0.3)
 * withdraws early. High ambush-bias factions (Sarab) don't withdraw as a unit — they
 * SCATTER in random directions and re-form at the home position later.
 */
public class RetreatGoal extends Goal {
	private final SoldierEntity soldier;
	private Vec3 fleeTarget;
	private int fleeTicks;

	public RetreatGoal(SoldierEntity soldier) {
		this.soldier = soldier;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.TARGET));
	}

	@Override
	public boolean canUse() {
		if (soldier.isScattered()) {
			return startFlee(true);
		}
		LivingEntity target = soldier.getTarget();
		if (target == null || soldier.getSquadId() == null) {
			return false;
		}
		Doctrine doctrine = soldier.doctrine();
		float losses = SquadManager.lossFraction(soldier.getSquadId());
		if (losses < doctrine.retreatThreshold()) {
			return false;
		}
		if (doctrine.ambushBias() >= 0.5F) {
			// scatter doctrine: everyone breaks in random directions, re-forms later
			SquadManager.scatterSquad(soldier.getSquadId(), soldier.level(), 600);
			return startFlee(true);
		}
		return startFlee(false);
	}

	private boolean startFlee(boolean random) {
		LivingEntity threat = soldier.getTarget() != null ? soldier.getTarget() : soldier.getLastHurtByMob();
		Vec3 away;
		if (random || threat == null) {
			double angle = soldier.getRandom().nextDouble() * Math.PI * 2;
			away = new Vec3(Math.cos(angle), 0, Math.sin(angle));
		} else {
			away = soldier.position().subtract(threat.position());
			away = new Vec3(away.x, 0, away.z);
			away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
		}
		fleeTarget = soldier.position().add(away.scale(32));
		fleeTicks = 0;
		soldier.setTarget(null);
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		return fleeTicks < 160 && fleeTarget != null;
	}

	@Override
	public void start() {
		soldier.getNavigation().moveTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, 1.25);
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		fleeTicks++;
		soldier.setTarget(null); // stay disengaged while withdrawing
		if (soldier.getNavigation().isDone() && fleeTicks < 150) {
			soldier.getNavigation().moveTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, 1.25);
		}
	}

	@Override
	public void stop() {
		fleeTarget = null;
	}
}
