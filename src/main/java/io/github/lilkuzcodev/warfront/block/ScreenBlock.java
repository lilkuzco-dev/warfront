package io.github.lilkuzcodev.warfront.block;

import com.mojang.serialization.MapCodec;
import io.github.lilkuzcodev.warfront.c2.DisplayBlockEntity;
import io.github.lilkuzcodev.warfront.c2.DisplayWallLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A thin, facing panel; neighbouring coplanar panels form a bounded 5x3 wall. */
public class ScreenBlock extends BaseEntityBlock {
	public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);

	public ScreenBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
		builder.add(HorizontalDirectionalBlock.FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction facing = context.getHorizontalDirection().getOpposite();
		BlockPos placed = context.getClickedPos();
		if (!DisplayWallLayout.fitsWithinBounds(placed, facing, candidate -> {
			BlockState state = context.getLevel().getBlockState(candidate);
			return state.getBlock() == this && state.getValue(HorizontalDirectionalBlock.FACING) == facing;
		})) {
			return null;
		}
		return defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.getBlockEntity(pos) instanceof DisplayBlockEntity display) display.reconcileWallBinding();
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(HorizontalDirectionalBlock.FACING)) {
			case NORTH -> box(0, 0, 0, 16, 16, 2);
			case SOUTH -> box(0, 0, 14, 16, 16, 16);
			case WEST -> box(0, 0, 0, 2, 16, 16);
			case EAST -> box(14, 0, 0, 16, 16, 16);
			default -> box(0, 0, 0, 16, 16, 2);
		};
	}

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
