package io.github.lilkuzcodev.warfront.systems;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Foots the faction bases. Their plates are single rigid jigsaw pieces, and
 * {@code beard_thin} cannot carry a 76-to-124-block plate across a slope — the downhill
 * edge hangs in the air, which players meet as "floating platform bases". This service
 * walks every column of a discovered base and extends the plate's underside down to
 * solid ground, copying the underside block itself so a stone rampart grows stone
 * footings and a dirt yard grows earthworks.
 *
 * <p>It runs on the base LEDGER, not on generation: existing worlds get their standing
 * bases footed the first time a player comes back to them, and the work is recorded in
 * {@link WarfrontState} so a base is footed exactly once. Castles are skipped — their
 * grounding belongs to CastleBuilder's occupancy-shaped site prep.
 *
 * <p>Rule 7 throughout: END_SERVER_TICK, budgeted columns per tick, no entity ticks.
 */
public final class SiteFoundations {

	private static final int SCAN_INTERVAL = 60;
	/** How near a player must be before a base is worth footing. */
	private static final int TRIGGER_DISTANCE = 160;
	/** Columns processed per tick; a metropolis bbox is ~60k columns, so ~2 seconds. */
	private static final int COLUMNS_PER_TICK = 1500;
	/** The plate underside is looked for this far above the structure box's bottom. */
	private static final int UNDERSIDE_SEARCH = 20;
	private static final int MAX_DEPTH = 48;

	private record Job(String key, int minX, int minZ, int maxX, int maxZ, int minY) {}

	private static final Deque<Job> QUEUE = new ArrayDeque<>();
	private static final Set<String> QUEUED = new HashSet<>();
	private static int cursor;

	private SiteFoundations() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerLevel level = server.overworld();
			if (server.getTickCount() % SCAN_INTERVAL == 0) scanNearPlayers(level);
			workOneBudget(level);
		});
	}

	private static void scanNearPlayers(ServerLevel level) {
		if (level.players().isEmpty()) return;
		WarfrontState state = WarfrontState.get(level.getServer());
		for (var entry : state.bases().entrySet()) {
			String key = entry.getKey();
			WarfrontState.Base base = entry.getValue();
			if ("castle".equals(base.tier) || base.founded || QUEUED.contains(key)) continue;
			if (base.bounds.size() != 6) continue;
			boolean near = false;
			for (ServerPlayer player : level.players()) {
				if (player.blockPosition().distManhattan(base.center) <= TRIGGER_DISTANCE) {
					near = true;
					break;
				}
			}
			if (!near) continue;
			QUEUE.add(new Job(key, base.bounds.get(0), base.bounds.get(2),
					base.bounds.get(3), base.bounds.get(5), base.bounds.get(1)));
			QUEUED.add(key);
		}
	}

	private static void workOneBudget(ServerLevel level) {
		Job job = QUEUE.peek();
		if (job == null) return;
		int width = job.maxX() - job.minX() + 1;
		int depth = job.maxZ() - job.minZ() + 1;
		int total = width * depth;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int filled = 0;
		int done = 0;
		while (cursor < total && done < COLUMNS_PER_TICK) {
			int x = job.minX() + cursor % width;
			int z = job.minZ() + cursor / width;
			cursor++;
			done++;
			if (!level.isLoaded(new BlockPos(x, job.minY(), z))) continue;
			// Top-down, filling under EVERY hanging span. A bottom-up "first non-air"
			// scan finds the terrain beneath the gap and declares the column grounded
			// while the building above it keeps hanging (measured: 455 built blocks
			// still airborne after the first cut of this pass). Leaves and non-solid
			// blocks (torches, plants) never get footings — a tree canopy on the plate
			// is scenery, not a floating platform.
			for (int y = job.minY() + UNDERSIDE_SEARCH; y > job.minY(); y--) {
				pos.set(x, y, z);
				BlockState here = level.getBlockState(pos);
				if (here.isAir() || !here.getFluidState().isEmpty()) continue;
				pos.set(x, y - 1, z);
				BlockState below = level.getBlockState(pos);
				boolean gapBelow = below.isAir() || !below.getFluidState().isEmpty() || below.canBeReplaced();
				if (!gapBelow) continue;
				if (here.is(net.minecraft.tags.BlockTags.LEAVES) || !here.isSolid()) continue;
				BlockState fill = here;
				if (fill.is(Blocks.GRASS_BLOCK) || fill.is(Blocks.DIRT_PATH) || fill.is(Blocks.FARMLAND)) {
					fill = Blocks.DIRT.defaultBlockState();
				}
				for (int fy = y - 1; fy >= Math.max(level.getMinY(), y - MAX_DEPTH); fy--) {
					pos.set(x, fy, z);
					BlockState gap = level.getBlockState(pos);
					if (!gap.isAir() && gap.getFluidState().isEmpty() && !gap.canBeReplaced()) {
						break; // reached real terrain (or the next span, already footed)
					}
					level.setBlock(pos, fill, 2);
					filled++;
				}
			}
		}
		if (cursor >= total) {
			QUEUE.poll();
			QUEUED.remove(job.key());
			cursor = 0;
			WarfrontState state = WarfrontState.get(level.getServer());
			WarfrontState.Base base = state.base(job.key());
			if (base != null) {
				base.founded = true;
				state.markBasesDirty();
			}
			Warfront.LOGGER.info("FOUNDATION_LAID {} ({} columns walked)", job.key(), total);
		}
	}
}
