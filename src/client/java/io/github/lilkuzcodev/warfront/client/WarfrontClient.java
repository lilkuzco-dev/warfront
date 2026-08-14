package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.entity.WarfrontEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class WarfrontClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRenderers.register(WarfrontEntities.SOLDIER, SoldierRenderer::new);
	}
}
