package io.github.lilkuzcodev.warfront.entity;

import io.github.lilkuzcodev.warfront.block.WarfrontBlocks;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The universal station-claim system (architecture note 2). A station block declares
 * its requirement (Phase 1: one operator); soldiers claim the nearest free station.
 * Claims are in-memory: cheap, and safely rebuilt after restarts by re-claiming.
 * Phase 4 assets (turrets, AA, silos) become new station blocks reusing this manager.
 */
public final class StationManager {
	/** dimension+pos key -> claiming soldier. */
	private static final Map<String, UUID> CLAIMS = new HashMap<>();

	public static @Nullable BlockPos claimNearest(SoldierEntity soldier) {
		if (!(soldier.level() instanceof ServerLevel level) || soldier.getHomePos() == null) {
			return null;
		}
		BlockPos home = soldier.getHomePos();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		// bases are compact; a bounded scan around the home anchor finds all stations
		for (BlockPos pos : BlockPos.betweenClosed(home.offset(-24, -6, -24), home.offset(24, 10, 24))) {
			if (!level.getBlockState(pos).is(WarfrontBlocks.SANDBAG_STATION)) {
				continue;
			}
			String key = key(level, pos);
			UUID holder = CLAIMS.get(key);
			if (holder != null && !holder.equals(soldier.getUUID()) && isHolderAlive(level, holder)) {
				continue;
			}
			double dist = pos.distSqr(soldier.blockPosition());
			if (dist < bestDist) {
				bestDist = dist;
				best = pos.immutable();
			}
		}
		if (best != null) {
			CLAIMS.put(key(level, best), soldier.getUUID());
		}
		return best;
	}

	public static boolean holds(SoldierEntity soldier, BlockPos pos) {
		if (!(soldier.level() instanceof ServerLevel level)) {
			return false;
		}
		return soldier.getUUID().equals(CLAIMS.get(key(level, pos)))
				&& level.getBlockState(pos).is(WarfrontBlocks.SANDBAG_STATION);
	}

	public static void release(SoldierEntity soldier) {
		CLAIMS.values().removeIf(uuid -> uuid.equals(soldier.getUUID()));
	}

	private static boolean isHolderAlive(ServerLevel level, UUID holder) {
		var entity = level.getEntity(holder);
		return entity != null && entity.isAlive();
	}

	private static String key(ServerLevel level, BlockPos pos) {
		return level.dimension().identifier() + ":" + pos.asLong();
	}

	private StationManager() {
	}
}
