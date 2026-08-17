package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.entity.CitizenEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

/** Uses a vanilla-owned texture; no copied or generated art is introduced. */
public final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, AvatarRenderState, PlayerModel> {
	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");

	public CitizenRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
				new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
	}

	@Override public AvatarRenderState createRenderState() { return new AvatarRenderState(); }
	@Override public Identifier getTextureLocation(AvatarRenderState state) { return TEXTURE; }

	@Override
	public void extractRenderState(CitizenEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.showHat = true;
		state.showJacket = true;
		state.showLeftSleeve = true;
		state.showRightSleeve = true;
		state.showLeftPants = true;
		state.showRightPants = true;
		state.showCape = false;
	}
}
