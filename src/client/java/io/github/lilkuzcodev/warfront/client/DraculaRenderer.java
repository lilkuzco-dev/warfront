package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.entity.DraculaEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

/** Player-model renderer for TenPlus1's attributed vampire skin. */
public final class DraculaRenderer extends HumanoidMobRenderer<DraculaEntity, AvatarRenderState, PlayerModel> {
	public DraculaRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
				new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.6F);
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new AvatarRenderState();
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return DraculaEntity.texture();
	}

	@Override
	public void extractRenderState(DraculaEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.showHat = true;
		state.showJacket = false;
		state.showLeftSleeve = false;
		state.showRightSleeve = false;
		state.showLeftPants = false;
		state.showRightPants = false;
		state.showCape = false;
	}
}
