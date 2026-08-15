package io.github.lilkuzcodev.warfront.block;

import io.github.lilkuzcodev.warfront.Warfront;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class WarfrontBlocks {
	/**
	 * The first station block (architecture note 2): a sandbag emplacement that
	 * declares "needs 1 operator". Soldiers claim and man it via StationManager.
	 */
	public static final Block SANDBAG_STATION = register("sandbag_station",
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.SAND)
					.strength(1.2F)
					.sound(SoundType.WOOL));

	/**
	 * Garrison bunk (v0.2.0): the reinforcement driver. BaseManager respawns one soldier
	 * per bunk per JSON interval — destroy the barracks and the base stops recovering.
	 */
	public static final Block BUNK = register("bunk", props -> new Block(props) {
		private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE = Block.box(0, 0, 0, 16, 6, 16);

		@Override
		protected net.minecraft.world.phys.shapes.VoxelShape getShape(
				net.minecraft.world.level.block.state.BlockState state,
				net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos,
				net.minecraft.world.phys.shapes.CollisionContext context) {
			return SHAPE;
		}
	}, BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_GREEN)
			.strength(0.8F)
			.sound(SoundType.WOOL)
			.noOcclusion());

	private static Block register(String name, BlockBehaviour.Properties properties) {
		return register(name, Block::new, properties);
	}

	private static Block register(String name, java.util.function.Function<BlockBehaviour.Properties, Block> factory,
			BlockBehaviour.Properties properties) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Warfront.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.setId(blockKey)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Warfront.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(itemKey)));
		return block;
	}

	public static void init() {
	}

	private WarfrontBlocks() {
	}
}
