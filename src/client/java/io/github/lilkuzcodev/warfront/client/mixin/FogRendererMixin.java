package io.github.lilkuzcodev.warfront.client.mixin;

import io.github.lilkuzcodev.warfront.client.VampireVeilClient;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vampire's veil, fog half. Without this the horizon keeps its bright daytime fog
 * band under the midnight sky — read from the first battery frames, where the veil's
 * night ended abruptly at a white horizon. The computed fog colour is collapsed to
 * near-black with the faintest red cast, so the distance dissolves into the dark the
 * way a real midnight does.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {

	@Inject(method = "computeFogColor", at = @At("TAIL"))
	private void warfront$veilFog(Camera camera, float partialTick, ClientLevel level,
			int renderDistance, float darkenWorldAmount, Vector4f color, CallbackInfo ci) {
		if (!VampireVeilClient.isActive()) return;
		color.set(color.x * 0.10F + 0.02F, color.y * 0.05F, color.z * 0.06F, color.w);
	}
}
