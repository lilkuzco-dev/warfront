package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.entity.CitizenEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

/** Player-model citizen with a distinct CC0 skin selected by synchronized profession. */
public final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, AvatarRenderState, PlayerModel> {
	public CitizenRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
				new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
	}

	@Override public AvatarRenderState createRenderState() { return new CitizenRenderState(); }
	@Override public Identifier getTextureLocation(AvatarRenderState state) {
		return Identifier.fromNamespaceAndPath("warfront", "textures/entity/citizen/"
				+ ((CitizenRenderState) state).profession + ".png");
	}

	@Override
	public void extractRenderState(CitizenEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		((CitizenRenderState) state).profession = entity.profession().id();
		state.showHat = true;
		state.showJacket = true;
		state.showLeftSleeve = true;
		state.showRightSleeve = true;
		state.showLeftPants = true;
		state.showRightPants = true;
		state.showCape = false;
	}
}
