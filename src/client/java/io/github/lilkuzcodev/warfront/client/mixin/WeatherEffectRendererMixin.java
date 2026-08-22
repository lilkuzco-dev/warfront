package io.github.lilkuzcodev.warfront.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.lilkuzcodev.warfront.client.VampireVeilClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The vampire's veil, weather half. Both wraps sit on call sites INSIDE the client-only
 * weather renderer — never on ClientLevel itself — so the integrated server's real
 * weather logic (fire, crops, cauldrons) never sees the fake storm. Rain level 1 makes
 * vanilla build precipitation columns; forcing their type to SNOW makes it snow in any
 * biome, desert included — it is Dracula's winter, not the biome's.
 *
 * <p>Density is set here too. Vanilla raises one precipitation column per (x, z) inside
 * the weather radius and skips a column only when the precipitation lookup answers NONE,
 * so the veil answers SNOW for one column in {@link #VEIL_SNOW_ONE_IN} and NONE for the
 * rest. A full field (every column) read as a blizzard indoors — the Count's halls were
 * wall-to-wall flakes — and 1-in-4 is the 75% cut that was asked for. The choice is a
 * fixed hash of the column's position, not a per-frame roll, so the sparse field stands
 * still relative to the architecture instead of flickering.
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {

	/** One column in this many carries snow while the veil holds; the rest stay clear. */
	@Unique
	private static final int VEIL_SNOW_ONE_IN = 4;

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
		if (!VampireVeilClient.isActive()) return original.call(level, pos);
		// NONE, not the biome's own answer: a raining biome must not show rain columns
		// between the snow while the veil holds.
		return warfront$veilColumn(pos.getX(), pos.getZ())
				? Biome.Precipitation.SNOW : Biome.Precipitation.NONE;
	}

	/**
	 * Whether the column at (x, z) snows under the veil. Murmur3's finaliser over the
	 * two coordinates: a plain {@code (x + z) % n} would draw diagonal stripes, and the
	 * low bits of {@code x * z} are mostly zero, so a real mixer is used.
	 */
	@Unique
	private static boolean warfront$veilColumn(int x, int z) {
		int h = x * 0x27D4EB2D ^ z * 0x165667B1;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		h *= 0xC2B2AE35;
		h ^= h >>> 16;
		return Math.floorMod(h, VEIL_SNOW_ONE_IN) == 0;
	}
}
