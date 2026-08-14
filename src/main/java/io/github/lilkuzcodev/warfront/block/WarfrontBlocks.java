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

	private static Block register(String name, BlockBehaviour.Properties properties) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Warfront.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, new Block(properties.setId(blockKey)));
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
