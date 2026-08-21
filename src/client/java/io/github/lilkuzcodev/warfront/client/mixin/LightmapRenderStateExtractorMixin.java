package io.github.lilkuzcodev.warfront.client.mixin;

import io.github.lilkuzcodev.warfront.client.VampireVeilClient;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vampire's veil, light half. A midnight sky over a noon-lit world reads as a bug,
 * so while the veil holds, sky light collapses to deep-night levels. The sky light's
 * COLOUR is deliberately left alone: a red-tinted sky light dyed every falling
 * snowflake salmon-brown (read from the first battery frames) — the blood belongs to
 * the moon, the snow stays white, and torch light is untouched throughout.
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {

	@Inject(method = "extract", at = @At("TAIL"))
	private void warfront$veilDarkness(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		if (!VampireVeilClient.isActive()) return;
		state.skyFactor = Math.min(state.skyFactor, 0.12F);
	}
}
