package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.civilization.CitizenProfession;
import io.github.lilkuzcodev.warfront.entity.CitizenEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Player-model citizen with a distinct CC0 skin selected by synchronized profession. */
public final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, AvatarRenderState, PlayerModel> {
	private static final Identifier MINER = texture("miner");
	private static final Identifier FARMER = texture("farmer");
	private static final Identifier BUILDER = texture("builder");
	private static final Identifier TRADER = texture("trader");
	private static final Identifier LABORER = texture("laborer");
	private static final PlayerSkin MINER_SKIN = skin("miner");
	private static final PlayerSkin FARMER_SKIN = skin("farmer");
	private static final PlayerSkin BUILDER_SKIN = skin("builder");
	private static final PlayerSkin TRADER_SKIN = skin("trader");
	private static final PlayerSkin LABORER_SKIN = skin("laborer");

	public CitizenRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
				new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
	}

	@Override public AvatarRenderState createRenderState() { return new CitizenRenderState(); }
	@Override public Identifier getTextureLocation(AvatarRenderState state) {
		return textureFor(((CitizenRenderState) state).profession);
	}

	@Override
	public void extractRenderState(CitizenEntity entity, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		CitizenProfession profession = entity.profession();
		((CitizenRenderState) state).profession = profession;
		// PlayerModel's 26.2 avatar pipeline also carries a skin in its render state.
		// Set it explicitly so a feature or renderer path can never fall back to Steve.
		state.skin = skinFor(profession);
		state.showHat = true;
		state.showJacket = true;
		state.showLeftSleeve = true;
		state.showRightSleeve = true;
		state.showLeftPants = true;
		state.showRightPants = true;
		state.showCape = false;
	}

	private static Identifier texture(String profession) {
		return Identifier.fromNamespaceAndPath("warfront", "textures/entity/citizen/" + profession + ".png");
	}

	// ClientAsset.ResourceTexture takes an ASSET ID, not a texture path: it derives the file
	// itself as `textures/<path>.png`. Handing it the full path this renderer uses for
	// getTextureLocation produced `warfront:textures/textures/entity/citizen/miner.png.png`,
	// which does not exist — so every citizen rendered as the magenta-and-black missing
	// texture. The avatar pipeline prefers the render state's skin over getTextureLocation,
	// so the correct override sitting right above it never got a look in. SoldierRenderer is
	// unaffected precisely because it never sets state.skin.
	private static PlayerSkin skin(String profession) {
		return new PlayerSkin(new ClientAsset.ResourceTexture(
				Identifier.fromNamespaceAndPath("warfront", "entity/citizen/" + profession)),
				null, null, PlayerModelType.WIDE, false);
	}

	private static Identifier textureFor(CitizenProfession profession) {
		return switch (profession) {
			case MINER -> MINER;
			case FARMER -> FARMER;
			case BUILDER -> BUILDER;
			case TRADER -> TRADER;
			case LABORER -> LABORER;
		};
	}

	/**
	 * The exact skin the renderer will use. Public so the render battery can assert the
	 * texture it points at actually resolves, rather than leaving a missing skin to be
	 * caught only by a human looking at a frame.
	 */
	public static PlayerSkin skinFor(CitizenProfession profession) {
		return switch (profession) {
			case MINER -> MINER_SKIN;
			case FARMER -> FARMER_SKIN;
			case BUILDER -> BUILDER_SKIN;
			case TRADER -> TRADER_SKIN;
			case LABORER -> LABORER_SKIN;
		};
	}
}
