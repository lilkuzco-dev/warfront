package io.github.lilkuzcodev.warfront.client;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Avatar-based render state (v0.2.0 Stage 5 fix): extends AvatarRenderState so
 * PlayerModel can pose the player-skin overlay parts instead of leaving them
 * frozen at origin (the Phase 1 "ghost limbs" bug).
 */
public class SoldierRenderState extends AvatarRenderState {
	public String faction = "";
}
