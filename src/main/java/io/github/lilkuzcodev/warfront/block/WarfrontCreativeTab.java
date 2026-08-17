package io.github.lilkuzcodev.warfront.block;

import io.github.lilkuzcodev.warfront.Warfront;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class WarfrontCreativeTab {
	public static final ResourceKey<CreativeModeTab> KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, Warfront.id("main"));

	public static List<Item> contents() {
		return List.of(WarfrontBlocks.SANDBAG_STATION.asItem(), WarfrontBlocks.BUNK.asItem(),
				WarfrontBlocks.SCREEN.asItem(), WarfrontBlocks.PROJECTOR.asItem());
	}

	public static void init() {
		CreativeModeTab tab = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
				.title(Component.translatable("itemGroup.warfront.main"))
				.icon(() -> new ItemStack(WarfrontBlocks.SCREEN))
				.displayItems((parameters, output) -> contents().forEach(output::accept))
				.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, tab);
	}

	private WarfrontCreativeTab() {
	}
}
