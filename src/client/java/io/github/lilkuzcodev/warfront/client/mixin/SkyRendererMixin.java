package io.github.lilkuzcodev.warfront.client.mixin;

import io.github.lilkuzcodev.warfront.client.VampireVeilClient;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vampire's veil, sky half. The render state is rewritten after vanilla extracts it,
 * so every convention stays vanilla's own: rather than guessing what angle "midnight"
 * is, the extracted angles are rotated by the time-delta between now and midnight — the
 * geometry is exactly what vanilla would draw at 18000, whatever the real clock says.
 * The moon's colour rides one uniform write inside renderMoon; the blood tint scales its
 * green and blue down and leaves red standing.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void warfront$veilMidnight(ClientLevel level, float partialTick, Camera camera,
			SkyRenderState state, CallbackInfo ci) {
		if (!VampireVeilClient.isActive()) return;
		long time = Math.floorMod(level.getOverworldClockTime(), 24000L);
		float delta = (float) (2.0 * Math.PI * ((18000.0 - time) / 24000.0));
		state.sunAngle += delta;
		state.moonAngle += delta;
		state.starAngle += delta;
		state.starBrightness = 1.0F;
		state.moonPhase = MoonPhase.FULL_MOON;
		state.skyColor = 0xFF07070F;
		state.sunriseAndSunsetColor = 0;
	}

	@ModifyArg(method = "renderMoon", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/DynamicUniforms;"
					+ "writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;)"
					+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), index = 1)
	private Vector4f warfront$bloodMoon(Vector4f color) {
		if (!VampireVeilClient.isActive()) return color;
		return new Vector4f(Math.max(color.x, 0.9F), color.y * 0.12F, color.z * 0.12F, color.w);
	}
}
