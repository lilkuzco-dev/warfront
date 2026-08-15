package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.Identifier;

/**
 * Player-model soldier with vanilla armor layers; per-faction uniform texture selected
 * from the render state (textures are recolors of the base soldier skin).
 *
 * <p>Stage 5 fix: Phase 1 baked the PLAYER layer into a plain {@link HumanoidModel},
 * which renders the skin-overlay cubes (sleeves/jacket/pants) without ever posing them
 * — the reported detached "ghost limb" shells. {@link PlayerModel} owns those parts and
 * poses them with the limbs; overlay visibility is off (uniforms are flat recolors),
 * hat layer on (vanilla hair depth).
 */
public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, AvatarRenderState, PlayerModel> {
	public SoldierRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
				new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(this,
				ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
				ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
				context.getEquipmentRenderer()));
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new SoldierRenderState();
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return SoldierEntity.textureFor(((SoldierRenderState) state).faction);
	}

	@Override
	public void extractRenderState(SoldierEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		((SoldierRenderState) state).faction = entity.getFaction();
		state.showHat = true;
		state.showJacket = false;
		state.showLeftSleeve = false;
		state.showRightSleeve = false;
		state.showLeftPants = false;
		state.showRightPants = false;
		state.showCape = false;
	}
}
