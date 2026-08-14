package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.resources.Identifier;

/**
 * Player-model soldier with vanilla armor layers; per-faction uniform texture selected
 * from the render state (textures are recolors of the base soldier skin).
 */
public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, SoldierRenderState, HumanoidModel<SoldierRenderState>> {
	public SoldierRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)),
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(this,
				ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
				ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
				context.getEquipmentRenderer()));
	}

	@Override
	public SoldierRenderState createRenderState() {
		return new SoldierRenderState();
	}

	@Override
	public Identifier getTextureLocation(SoldierRenderState state) {
		return SoldierEntity.textureFor(state.faction);
	}

	@Override
	public void extractRenderState(SoldierEntity entity, SoldierRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.faction = entity.getFaction();
	}
}
