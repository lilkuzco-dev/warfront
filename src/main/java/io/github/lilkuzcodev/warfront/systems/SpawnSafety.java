package io.github.lilkuzcodev.warfront.systems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Shared spawn placement that favors nearby open ground instead of roof heightmaps. */
public final class SpawnSafety {
	public static @org.jspecify.annotations.Nullable BlockPos openGroundNear(
			ServerLevel level, int centerX, int centerZ, int radius) {
		BlockPos best = null;
		int bestY = Integer.MAX_VALUE;
		int bestDistance = Integer.MAX_VALUE;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int x = centerX + dx;
				int z = centerZ + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos candidate = new BlockPos(x, y, z);
				if (!level.getBlockState(candidate).isAir()
						|| !level.getBlockState(candidate.above()).isAir()
						|| level.getBlockState(candidate.below()).isAir()) continue;
				int distance = dx * dx + dz * dz;
				if (y < bestY || y == bestY && distance < bestDistance) {
					best = candidate;
					bestY = y;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	private SpawnSafety() {}
}
