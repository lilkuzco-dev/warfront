package io.github.lilkuzcodev.warfront.entity.ai;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.civilization.CitizenProfession;
import io.github.lilkuzcodev.warfront.entity.CitizenEntity;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

/** Tier-1 work: pathfinding and world interaction exist only while embodied. */
public final class CitizenWorkGoal extends Goal {
	private static final TagKey<Block> MINEABLE = TagKey.create(Registries.BLOCK, Warfront.id("citizen_mineable"));
	private static final int RADIUS = 12;

	private final CitizenEntity citizen;
	private @Nullable BlockPos target;
	private int nextSearch;

	public CitizenWorkGoal(CitizenEntity citizen) {
		this.citizen = citizen;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (--nextSearch > 0 || !(citizen.level() instanceof ServerLevel level)) return false;
		nextSearch = adjustedTickDelay(40);
		target = findTarget(level);
		return target != null;
	}

	@Override
	public boolean canContinueToUse() {
		return target != null && citizen.level() instanceof ServerLevel;
	}

	@Override
	public void start() {
		moveToTarget();
	}

	@Override
	public void tick() {
		if (target == null || !(citizen.level() instanceof ServerLevel level)) return;
		citizen.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
		if (target.distToCenterSqr(citizen.position()) > 3.0 * 3.0) {
			if (citizen.getNavigation().isDone() || citizen.tickCount % 30 == 0) moveToTarget();
			return;
		}
		citizen.getNavigation().stop();
		citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		if (!citizen.advanceEmbodiedWork()) return;
		completeWork(level);
		target = null;
		nextSearch = adjustedTickDelay(20);
	}

	private @Nullable BlockPos findTarget(ServerLevel level) {
		CitizenProfession profession = citizen.profession();
		if (profession == CitizenProfession.TRADER || profession == CitizenProfession.LABORER) {
			return citizen.homePos();
		}
		BlockPos center = citizen.homePos();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RADIUS, -4, -RADIUS),
				center.offset(RADIUS, 4, RADIUS))) {
			if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
			BlockState state = level.getBlockState(pos);
			boolean match = switch (profession) {
				case MINER -> state.is(MINEABLE) && state.getDestroySpeed(level, pos) >= 0;
				case FARMER -> state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
				case BUILDER -> state.is(Blocks.CRAFTING_TABLE);
				default -> false;
			};
			if (!match) continue;
			double distance = pos.distToCenterSqr(citizen.position());
			if (distance < bestDistance) {
				bestDistance = distance;
				best = pos.immutable();
			}
		}
		return best;
	}

	private void completeWork(ServerLevel level) {
		CitizenProfession profession = citizen.profession();
		BlockState state = level.getBlockState(target);
		if (profession == CitizenProfession.MINER && level.getGameRules().get(GameRules.MOB_GRIEFING)
				&& state.is(MINEABLE)) {
			List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), citizen,
					new ItemStack(profession.tool()));
			level.destroyBlock(target, false, citizen);
			for (ItemStack drop : drops) {
				String id = BuiltInRegistries.ITEM.getKey(drop.getItem()).toString();
				citizen.store(id, drop.getCount());
			}
			return;
		}
		if (profession == CitizenProfession.FARMER && level.getGameRules().get(GameRules.MOB_GRIEFING)
				&& state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
			List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), citizen,
					new ItemStack(profession.tool()));
			level.setBlockAndUpdate(target, crop.getStateForAge(0));
			for (ItemStack drop : drops) {
				String id = BuiltInRegistries.ITEM.getKey(drop.getItem()).toString();
				citizen.store(id, drop.getCount());
			}
			return;
		}
		if (profession == CitizenProfession.BUILDER && state.is(Blocks.CRAFTING_TABLE)
				&& citizen.consume("minecraft:raw_iron", 1)) {
			if (citizen.consume("minecraft:oak_log", 1)) {
				citizen.store("minecraft:oak_planks", 2);
			} else {
				// A failed two-input craft changes nothing.
				citizen.store("minecraft:raw_iron", 1);
			}
		}
		// Traders and laborers visibly run their routes. Their transfers/wages are
		// settled by the same city economy tick used for abstract citizens.
	}

	private void moveToTarget() {
		if (target != null) citizen.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.9);
	}

	@Override
	public void stop() {
		target = null;
		citizen.getNavigation().stop();
	}
}
