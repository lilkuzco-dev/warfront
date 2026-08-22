package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.entity.DraculaEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Player-model renderer for TenPlus1's attributed vampire skin, with a cape.
 *
 * <p>The skin is set on the render state, not only returned from getTextureLocation:
 * 26.2's avatar pipeline draws {@code state.skin}, whose default is Steve, and it wins
 * over the texture override. Count Dracula shipped as Steve from 0.4.9 to 0.4.19 for
 * exactly that reason — the same trap the citizen renderer fell into at 0.4.3 — and the
 * render battery now asserts both of his textures resolve, as it does for citizens.
 * {@code ClientAsset.ResourceTexture} takes an asset id and derives
 * {@code textures/<id>.png} itself.
 */
public final class DraculaRenderer extends HumanoidMobRenderer<DraculaEntity, AvatarRenderState, PlayerModel> {
	private static final PlayerSkin SKIN = new PlayerSkin(
			new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("warfront", "entity/dracula")),
			new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("warfront", "entity/dracula_cape")),
			null, PlayerModelType.WIDE, false);

	public DraculaRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
				new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.6F);
		addLayer(new CapeLayer(this, context.getModelSet(), context.getEquipmentAssets()));
	}

	/** The exact skin drawn, so the render battery can assert its textures resolve. */
	public static PlayerSkin skin() {
		return SKIN;
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new AvatarRenderState();
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return SKIN.body().texturePath();
	}

	@Override
	public void extractRenderState(DraculaEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.skin = SKIN;
		state.showHat = true;
		state.showJacket = false;
		state.showLeftSleeve = false;
		state.showRightSleeve = false;
		state.showLeftPants = false;
		state.showRightPants = false;
		state.showCape = true;
		// A mob has no cloak physics; give the cape a stride-driven lean and a slow sway.
		state.capeLean = 6.0F + state.walkAnimationSpeed * 24.0F;
		state.capeFlap = 4.0F + Mth.sin(state.ageInTicks * 0.12F) * 4.0F;
		state.capeLean2 = Mth.sin(state.ageInTicks * 0.07F) * 2.0F;
	}
}
