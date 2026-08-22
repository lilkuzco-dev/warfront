package io.github.lilkuzcodev.warfront.systems;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Foots the faction bases. Their plates are single rigid jigsaw pieces, and
 * {@code beard_thin} cannot carry a 76-to-124-block plate across a slope — the downhill
 * edge hangs in the air, which players meet as "floating platform bases". This service
 * extends each plate's underside down to solid ground, copying the underside block itself
 * so a stone rampart grows stone footings and a dirt yard grows earthworks.
 *
 * <h2>What it foots, exactly</h2>
 * Every plate template is a solid slab at its own y=0 — measured across all 24 plate NBTs
 * on 2026-08-22, 100% of columns have their lowest block at template y=0 — so a piece's
 * underside is simply its bounding box's bottom row. Each column of each PIECE is walked
 * once: find the lowest block the piece put there, and if the block beneath it is a gap,
 * fill downward until terrain. Nothing above the underside is ever touched.
 *
 * <h2>What 0.4.17–0.4.19 did instead, and why it was destructive</h2>
 * It walked every column of the base's whole STRUCTURE box — the jigsaw sprawl box,
 * 150–250 blocks wide, most of it not base at all — and, top-down, extruded <em>every</em>
 * solid block that had air beneath it. That "foots every hanging span" rule cannot tell a
 * hanging plate from a roof over a room: it filled interiors solid from ceiling to floor,
 * and it did it to anything else inside the sprawl box. Reported from play on 2026-08-22:
 * a Waldschatten witch hut 148 blocks from a town's corner, and the town's own buildings,
 * both packed with copies of their own roofs. Its acceptance test — "zero built blocks
 * hanging" — is one a fill-everything pass satisfies by construction.
 *
 * <p>Two more rules follow from the report:
 * <ul>
 *   <li>A column that belongs to <em>another</em> structure's piece is never touched, even
 *       when it sits inside one of ours. The base is the guest there, not the host.</li>
 *   <li>A column whose chunk is not loaded is skipped for now, not forever. The old pass
 *       marked the base founded after one walk regardless, so any chunk outside view
 *       distance at that moment stayed hanging for the life of the world.</li>
 * </ul>
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
	/** Columns processed per tick; a metropolis plate is ~15k columns, so ~10 ticks. */
	private static final int COLUMNS_PER_TICK = 1500;
	private static final int MAX_DEPTH = 48;
	/** A pass that had to skip unloaded columns is retried, but not every scan. */
	private static final int RETRY_INTERVAL = 600;
	/** After this many incomplete passes the base is recorded as founded anyway, loudly. */
	private static final int MAX_PASSES = 6;

	/** Every warfront base structure. A column claimed by any OTHER structure is off limits. */
	private static final TagKey<Structure> BASES = TagKey.create(Registries.STRUCTURE, Warfront.id("bases"));

	private record Job(String key, List<BoundingBox> pieces) {}

	private static final Deque<Job> QUEUE = new ArrayDeque<>();
	private static final Set<String> QUEUED = new HashSet<>();
	private static final Map<String, Long> NEXT_ATTEMPT = new HashMap<>();
	private static final Map<String, Integer> PASSES = new HashMap<>();
	private static int pieceCursor;
	private static int columnCursor;
	private static int filled;
	private static int maxFillY = Integer.MIN_VALUE;
	private static int footed;
	private static int unloaded;
	private static int foreign;

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
		long now = level.getGameTime();
		for (var entry : state.bases().entrySet()) {
			String key = entry.getKey();
			WarfrontState.Base base = entry.getValue();
			if ("castle".equals(base.tier) || base.founded || QUEUED.contains(key)) continue;
			if (now < NEXT_ATTEMPT.getOrDefault(key, 0L)) continue;
			boolean near = false;
			for (ServerPlayer player : level.players()) {
				if (player.blockPosition().distManhattan(base.center) <= TRIGGER_DISTANCE) {
					near = true;
					break;
				}
			}
			if (!near) continue;
			List<BoundingBox> pieces = piecesOf(level, key, base);
			if (pieces.isEmpty()) continue; // start chunk not loaded yet; next scan
			QUEUE.add(new Job(key, pieces));
			QUEUED.add(key);
		}
	}

	/**
	 * The real pieces of the base, from its structure start. The ledger's {@code bounds} is
	 * the sprawl box and is deliberately not used: it is the thing that made the old pass
	 * destructive.
	 */
	private static List<BoundingBox> piecesOf(ServerLevel level, String key, WarfrontState.Base base) {
		int at = key.indexOf('@');
		if (at <= 0) return List.of();
		ResourceKey<Structure> structureKey = ResourceKey.create(Registries.STRUCTURE, Warfront.id(key.substring(0, at)));
		Optional<Holder.Reference<Structure>> structure =
				level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(structureKey);
		if (structure.isEmpty()) return List.of();
		StructureStart start = level.structureManager().getStructureAt(base.center, structure.get().value());
		if (!start.isValid()) return List.of();
		List<BoundingBox> boxes = new ArrayList<>();
		for (StructurePiece piece : start.getPieces()) {
			boxes.add(piece.getBoundingBox());
		}
		return boxes;
	}

	private static void workOneBudget(ServerLevel level) {
		Job job = QUEUE.peek();
		if (job == null) return;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int done = 0;
		while (pieceCursor < job.pieces().size() && done < COLUMNS_PER_TICK) {
			BoundingBox box = job.pieces().get(pieceCursor);
			int width = box.getXSpan();
			int total = width * box.getZSpan();
			boolean touched = false;
			while (columnCursor < total && done < COLUMNS_PER_TICK) {
				int x = box.minX() + columnCursor % width;
				int z = box.minZ() + columnCursor / width;
				columnCursor++;
				done++;
				touched |= footColumn(level, box, x, z, pos);
			}
			if (touched) {
				SpawnSafety.rescueBuried(level, box.minX(), box.minZ(), box.maxX(), box.maxZ());
			}
			if (columnCursor >= total) {
				pieceCursor++;
				columnCursor = 0;
			}
		}
		if (pieceCursor >= job.pieces().size()) {
			finish(level, job);
		}
	}

	/** Foots one column of one piece. Returns true if any block was set. */
	private static boolean footColumn(ServerLevel level, BoundingBox box, int x, int z, BlockPos.MutableBlockPos pos) {
		pos.set(x, box.minY(), z);
		if (!level.isLoaded(pos)) {
			unloaded++;
			return false;
		}
		// Somebody else's structure owns this column: a hut, a village house, a haunted
		// house. Our piece being pasted over it is already one collision too many; the
		// footing will not be a second.
		if (level.structureManager().getStructureWithPieceAt(pos, holder -> !holder.is(BASES)).isValid()) {
			foreign++;
			return false;
		}
		// The underside: the lowest block this piece put in the column. For every plate
		// that is the bottom row itself; for a small socket piece it can sit higher.
		int underside = -1;
		BlockState here = null;
		for (int y = box.minY(); y <= box.maxY(); y++) {
			pos.set(x, y, z);
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || !state.getFluidState().isEmpty()) continue;
			underside = y;
			here = state;
			break;
		}
		if (here == null) return false;
		// Leaves and non-solid blocks (torches, plants, a tree on the plate) are scenery, not
		// a floating platform.
		if (here.is(BlockTags.LEAVES) || !here.isSolid()) return false;
		pos.set(x, underside - 1, z);
		BlockState below = level.getBlockState(pos);
		boolean gapBelow = below.isAir() || !below.getFluidState().isEmpty() || below.canBeReplaced();
		if (!gapBelow) {
			footed++;
			return false;
		}
		BlockState fill = here;
		if (fill.is(Blocks.GRASS_BLOCK) || fill.is(Blocks.DIRT_PATH) || fill.is(Blocks.FARMLAND)) {
			fill = Blocks.DIRT.defaultBlockState();
		}
		boolean touched = false;
		for (int fy = underside - 1; fy >= Math.max(level.getMinY(), underside - MAX_DEPTH); fy--) {
			pos.set(x, fy, z);
			BlockState gap = level.getBlockState(pos);
			if (!gap.isAir() && gap.getFluidState().isEmpty() && !gap.canBeReplaced()) {
				break; // reached real terrain
			}
			level.setBlock(pos, fill, 2);
			filled++;
			maxFillY = Math.max(maxFillY, fy);
			touched = true;
		}
		footed++;
		return touched;
	}

	private static void finish(ServerLevel level, Job job) {
		QUEUE.poll();
		QUEUED.remove(job.key());
		int passes = PASSES.merge(job.key(), 1, Integer::sum);
		int lowestPieceBottom = job.pieces().stream().mapToInt(BoundingBox::minY).min().orElse(Integer.MAX_VALUE);
		boolean complete = unloaded == 0;
		WarfrontState state = WarfrontState.get(level.getServer());
		WarfrontState.Base base = state.base(job.key());
		if (base != null && (complete || passes >= MAX_PASSES)) {
			base.founded = true;
			state.markBasesDirty();
			PASSES.remove(job.key());
			NEXT_ATTEMPT.remove(job.key());
		} else {
			NEXT_ATTEMPT.put(job.key(), level.getGameTime() + RETRY_INTERVAL);
		}
		// maxFillY < lowest piece bottom is the invariant the whole rewrite exists for:
		// nothing above any underside was written. It is logged rather than trusted.
		Warfront.LOGGER.info(
				"FOUNDATION_{} {} pieces={} columns_footed={} blocks_filled={} max_fill_y={} lowest_piece_bottom={} "
						+ "foreign_columns_skipped={} unloaded_columns={} pass={}",
				complete ? "LAID" : (passes >= MAX_PASSES ? "LAID_INCOMPLETE" : "PARTIAL"),
				job.key(), job.pieces().size(), footed, filled,
				filled > 0 ? maxFillY : "n/a", lowestPieceBottom, foreign, unloaded, passes);
		pieceCursor = 0;
		columnCursor = 0;
		filled = 0;
		maxFillY = Integer.MIN_VALUE;
		footed = 0;
		unloaded = 0;
		foreign = 0;
	}
}
