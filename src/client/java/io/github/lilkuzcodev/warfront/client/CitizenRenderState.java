package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.civilization.CitizenProfession;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** Profession is copied from synchronized entity data before each citizen render. */
public final class CitizenRenderState extends AvatarRenderState {
	public CitizenProfession profession = CitizenProfession.LABORER;
}
