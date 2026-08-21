package io.github.lilkuzcodev.warfront.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.lilkuzcodev.warfront.client.VampireVeilClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The vampire's veil, weather half. Both wraps sit on call sites INSIDE the client-only
 * weather renderer — never on ClientLevel itself — so the integrated server's real
 * weather logic (fire, crops, cauldrons) never sees the fake storm. Rain level 1 makes
 * vanilla build precipitation columns; forcing their type to SNOW makes it snow in any
 * biome, desert included — it is Dracula's winter, not the biome's.
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
	private float warfront$veilStorm(ClientLevel level, float partialTick, Operation<Float> original) {
		float real = original.call(level, partialTick);
		return VampireVeilClient.isActive() ? 1.0F : real;
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;"
					+ "getPrecipitationAt(Lnet/minecraft/core/BlockPos;)"
					+ "Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
	private Biome.Precipitation warfront$veilSnow(ClientLevel level, BlockPos pos,
			Operation<Biome.Precipitation> original) {
		if (VampireVeilClient.isActive()) return Biome.Precipitation.SNOW;
		return original.call(level, pos);
	}
}
