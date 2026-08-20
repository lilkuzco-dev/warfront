package io.github.lilkuzcodev.warfront.entity.ai;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.civilization.CivilizationMath;
import io.github.lilkuzcodev.warfront.civilization.CitizenProfession;
import io.github.lilkuzcodev.warfront.entity.CitizenEntity;
import io.github.lilkuzcodev.warfront.systems.SpawnSafety;
import java.util.ArrayList;
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
	private static final int SEARCH_RADIUS = 32;
	private static final int SEARCH_Y = 8;
	private static final int MAX_CANDIDATES = 256;
	private static final int MAX_TRAVEL_TICKS = 200;

	private final CitizenEntity citizen;
	private @Nullable BlockPos target;
	private int nextSearch;
	private int travelTicks;
	private @Nullable BlockPos blockedTarget;
	private int blockedUntil;

	public CitizenWorkGoal(CitizenEntity citizen) {
		this.citizen = citizen;
		nextSearch = citizen.getRandom().nextInt(40);
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
		return target != null && citizen.level() instanceof ServerLevel
				&& travelTicks < MAX_TRAVEL_TICKS;
	}

	@Override
	public void start() {
		travelTicks = 0;
		moveToTarget();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		if (target == null || !(citizen.level() instanceof ServerLevel level)) return;
		citizen.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
		if (target.distToCenterSqr(citizen.position()) > 3.0 * 3.0) {
			travelTicks++;
			if (citizen.getNavigation().isDone() || citizen.tickCount % 30 == 0) moveToTarget();
			return;
		}
		citizen.getNavigation().stop();
		travelTicks = 0;
		if (citizen.tickCount % 20 == 0) {
			citizen.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		}
		if (!citizen.advanceEmbodiedWork()) return;
		completeWork(level);
		target = null;
		nextSearch = adjustedTickDelay(20 + citizen.getRandom().nextInt(40));
	}

	private @Nullable BlockPos findTarget(ServerLevel level) {
		CitizenProfession profession = citizen.profession();
		BlockPos center = citizen.homePos();
		List<BlockPos> primary = new ArrayList<>();
		List<BlockPos> fallback = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -SEARCH_Y, -SEARCH_RADIUS),
				center.offset(SEARCH_RADIUS, SEARCH_Y, SEARCH_RADIUS))) {
			if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
			if (blockedTarget != null && citizen.tickCount < blockedUntil && blockedTarget.equals(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (isPrimaryWorksite(profession, state, level, pos)) {
				if (primary.size() < MAX_CANDIDATES) primary.add(pos.immutable());
			} else if (isFallbackWorksite(profession, state)) {
				if (fallback.size() < MAX_CANDIDATES) fallback.add(pos.immutable());
			}
		}
		List<BlockPos> candidates = primary.isEmpty() ? fallback : primary;
		if (candidates.isEmpty()) return routePoint(level, center);
		int cycle = citizen.tickCount / (int) CivilizationMath.WORK_CYCLE_TICKS;
		int index = Math.floorMod(Long.hashCode(citizen.serial()) + cycle, candidates.size());
		return candidates.get(index);
	}

	private static boolean isPrimaryWorksite(CitizenProfession profession, BlockState state,
			ServerLevel level, BlockPos pos) {
		return switch (profession) {
			case MINER -> state.is(MINEABLE) && state.getDestroySpeed(level, pos) >= 0;
			case FARMER -> state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
			case BUILDER -> isBuilderWorkstation(state);
			case TRADER -> isContainer(state);
			case LABORER -> isContainer(state) || state.is(Blocks.HAY_BLOCK);
		};
	}

	private static boolean isFallbackWorksite(CitizenProfession profession, BlockState state) {
		return switch (profession) {
			case MINER -> state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.FURNACE)
					|| state.is(Blocks.SMITHING_TABLE) || state.is(Blocks.STONECUTTER);
			case FARMER -> state.is(Blocks.COMPOSTER) || state.is(Blocks.HAY_BLOCK)
					|| state.is(Blocks.FARMLAND);
			case BUILDER -> state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.FURNACE)
					|| state.is(Blocks.BARREL);
			case TRADER -> state.is(Blocks.LECTERN) || state.is(Blocks.BELL);
			case LABORER -> state.is(Blocks.SMOKER) || state.is(Blocks.FURNACE);
		};
	}

	private static boolean isBuilderWorkstation(BlockState state) {
		return state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.STONECUTTER)
				|| state.is(Blocks.SMITHING_TABLE);
	}

	private static boolean isContainer(BlockState state) {
		return state.is(Blocks.BARREL) || state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
	}

	private @Nullable BlockPos routePoint(ServerLevel level, BlockPos center) {
		long phase = citizen.serial() + citizen.tickCount / CivilizationMath.WORK_CYCLE_TICKS;
		double angle = phase * 2.399963229728653;
		int distance = 5 + Math.floorMod(Long.hashCode(phase), 8);
		int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
		int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
		return SpawnSafety.openGroundNear(level, x, z, 2);
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
		if (profession == CitizenProfession.BUILDER && isBuilderWorkstation(state)
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
		if (target != null && travelTicks >= MAX_TRAVEL_TICKS) {
			blockedTarget = target;
			blockedUntil = citizen.tickCount + 600;
		}
		target = null;
		travelTicks = 0;
		citizen.getNavigation().stop();
	}
}
