package io.github.lilkuzcodev.warfront.block;

import com.mojang.serialization.MapCodec;
import io.github.lilkuzcodev.warfront.c2.DisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** War-room table projector carrying the same feed binding as a wall screen. */
public class ProjectorBlock extends BaseEntityBlock {
	public static final MapCodec<ProjectorBlock> CODEC = simpleCodec(ProjectorBlock::new);

	public ProjectorBlock(Properties properties) { super(properties); }

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return box(2, 0, 2, 14, 5, 14);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new DisplayBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide() || type != WarfrontBlockEntities.DISPLAY ? null
				: (l, p, s, be) -> DisplayBlockEntity.serverTick(l, p, s, (DisplayBlockEntity) be);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
			BlockHitResult hit) {
		if (level.getBlockEntity(pos) instanceof DisplayBlockEntity display) display.interact(player);
		return InteractionResult.SUCCESS;
	}
}
