package io.github.lilkuzcodev.warfront.block;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.c2.DisplayBlockEntity;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class WarfrontBlockEntities {
	public static final BlockEntityType<DisplayBlockEntity> DISPLAY = new BlockEntityType<>(
			DisplayBlockEntity::new, Set.of(WarfrontBlocks.SCREEN, WarfrontBlocks.PROJECTOR));

	public static void init() {
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Warfront.id("display"), DISPLAY);
	}

	private WarfrontBlockEntities() {
	}
}
