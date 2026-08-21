package io.github.lilkuzcodev.warfront.worldgen;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.systems.BaseManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Builds Warfront's monumental castles, because Minecraft cannot.
 *
 * <p>A vanilla structure reaches at most <b>128 blocks from its start chunk</b>:
 * {@code ChunkGenerator.createReferences} scans a hardcoded 8-chunk radius for structure
 * starts, and {@code max_distance_from_center} is codec-capped at 128 besides. The castles
 * are 501+ blocks across. Measured on the live server on 2026-08-20: {@code /locate} found
 * a castle, the city record was seeded and ticking, and the site held zero castle blocks,
 * zero soldiers and zero citizens. They had never generated, in any world, since the day
 * they were imported.
 *
 * <p>So the castle is not a structure any more. The structure set still exists — that is
 * what {@code /locate} and the base/castle clearance are built on — and this service finds
 * the sites the set placed, then pastes the template itself once the ground is loaded.
 *
 * <p><b>Which castle stands at a site is read from the world, never rolled.</b> The
 * structure set records a real {@code StructureStart} for whichever castle structure it
 * chose for the chunk, and {@code /locate}, base discovery and the city ledger all read
 * that record. An earlier version of this class picked the faction with a private hash of
 * the chunk coordinates, so the site the game called Dracula's castle could get a Vostok
 * paste — the model was wrong roughly half the time, and it was wrong <i>relative to every
 * other system</i>, which is worse than a coin flip. The paste is also anchored to the
 * start's own bounding box for the same reason: blocks, ledger and citizens must agree
 * about where the castle is.
 *
 * <p><b>Rule 7.</b> This runs on {@code END_SERVER_TICK}, never on an entity or
 * block-entity tick, and every site it has finished is recorded in {@link CastleSites} so
 * the work survives a restart and is never done twice. It is deliberately budgeted: a
 * castle is millions of blocks, and pasting that in one tick would stall the server for
 * seconds, so it goes down in slices with a per-tick cap.
 */
public final class CastleBuilder {

	/** How near a player must be before a castle is worth building. */
	private static final int TRIGGER_CHUNKS = 12;
	/**
	 * Slice width in blocks. A slice is bounded so a multi-million-block paste never
	 * stalls a tick; 24 rather than the old 48 because each slice now also carves the
	 * terrain above the site and grounds the template's underside (see below).
	 */
	private static final int SLICE_BLOCKS = 24;
	private static final int SCAN_INTERVAL = 100;
	/** Chunks generated per tick while a castle is going down. Keeps the paste off the tick budget. */
	private static final int CHUNKS_PER_TICK = 6;
	/** Ground-height samples per axis when choosing the paste height (so 5x5 = 25 columns). */
	private static final int GROUND_SAMPLES = 5;
	/** How far below the template's underside a foundation column will reach before giving up. */
	private static final int FOUNDATION_MAX_DEPTH = 64;
	/** The template's underside is looked for this far above the origin when grounding a column. */
	private static final int FOUNDATION_SEARCH_HEIGHT = 24;

	private static final Deque<PendingCastle> QUEUE = new ArrayDeque<>();
	private static final Set<String> QUEUED = new HashSet<>();
	/**
	 * Placement said "structure chunk", generation recorded no castle start (a biome can
	 * veto every entry in the set). Pasting anything there would create a castle no other
	 * system knows about, so the site is skipped — remembered per boot so the chunk is not
	 * re-read from disk every scan.
	 */
	private static final Set<String> NO_START = new HashSet<>();

	/** {@code originY == Integer.MIN_VALUE} means the paste height is not yet resolved. */
	private record PendingCastle(String key, Identifier template, int minX, int minZ,
			int originY, int nextX) {
		BlockPos origin() {
			return new BlockPos(minX, originY, minZ);
		}
	}

	private CastleBuilder() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerLevel level = server.overworld();
			if (server.getTickCount() % SCAN_INTERVAL == 0) scanNearPlayers(level);
			buildOneSlice(level);
		});
	}

	/**
	 * Replays the castle set's own placement maths near each player. This is the same
	 * arithmetic the structure set uses, so a castle lands exactly where {@code /locate}
	 * says it does.
	 */
	private static void scanNearPlayers(ServerLevel level) {
		if (level.players().isEmpty()) return;
		Optional<Holder.Reference<StructureSet>> castles = level.registryAccess()
				.lookupOrThrow(Registries.STRUCTURE_SET)
				.get(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE_SET,
						Warfront.id("grand_castles")));
		if (castles.isEmpty()) return;
		StructurePlacement placement = castles.get().value().placement();
		var state = level.getChunkSource().getGeneratorState();
		CastleSites sites = CastleSites.get(level.getServer());

		for (ServerPlayer player : level.players()) {
			ChunkPos centre = player.chunkPosition();
			for (int dx = -TRIGGER_CHUNKS; dx <= TRIGGER_CHUNKS; dx++) {
				for (int dz = -TRIGGER_CHUNKS; dz <= TRIGGER_CHUNKS; dz++) {
					int cx = centre.x() + dx;
					int cz = centre.z() + dz;
					if (!placement.isStructureChunk(state, cx, cz)) continue;
					String key = cx + "," + cz;
					if (sites.isBuilt(key) || QUEUED.contains(key) || NO_START.contains(key)) continue;
					enqueue(level, key, cx, cz);
				}
			}
		}
	}

	/**
	 * Queues one specific castle at one specific place. Exists so a test can prove every
	 * castle type pastes, rather than proving whichever one the site holds.
	 */
	public static void enqueueForTest(String faction, BlockPos origin) {
		String key = "test/" + faction + "/" + origin.toShortString();
		QUEUE.add(new PendingCastle(key, Warfront.id(faction + "/castle"),
				origin.getX(), origin.getZ(), origin.getY(), 0));
		QUEUED.add(key);
	}

	/**
	 * Reads which castle the world actually placed at this site and queues that one. The
	 * chunk's structure starts are the single source of truth here — they are what
	 * {@code /locate} reports and what base discovery registers.
	 */
	private static void enqueue(ServerLevel level, String key, int chunkX, int chunkZ) {
		ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_STARTS, true);
		Identifier structureId = null;
		StructureStart start = null;
		for (var entry : chunk.getAllStarts().entrySet()) {
			Identifier id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(entry.getKey());
			if (id != null && id.getNamespace().equals(Warfront.MOD_ID) && id.getPath().endsWith("_castle")
					&& entry.getValue().isValid()) {
				structureId = id;
				start = entry.getValue();
				break;
			}
		}
		if (start == null) {
			NO_START.add(key);
			Warfront.LOGGER.info("CASTLE_SKIPPED site {} has no castle structure start (biome veto)", key);
			return;
		}
		String faction = structureId.getPath().substring(0,
				structureId.getPath().length() - "_castle".length());
		Identifier templateId = Warfront.id(faction + "/castle");
		Optional<StructureTemplate> template = level.getServer().getStructureManager().get(templateId);
		if (template.isEmpty()) {
			NO_START.add(key);
			Warfront.LOGGER.error("CASTLE_SKIPPED template {} is missing for site {}", templateId, key);
			return;
		}
		int size = Math.max(template.get().getSize().getX(), template.get().getSize().getZ());

		// Anchor the paste to the start's own bounding box, so blocks, ledger, citizens
		// and /locate all agree about where the castle is. If the recorded box is narrower
		// than the template (max_distance_from_center clamps what jigsaw records), share
		// its centre instead of its corner.
		BoundingBox box = start.getBoundingBox();
		int minX;
		int minZ;
		if (box.getXSpan() >= size - 16 && box.getZSpan() >= size - 16) {
			minX = box.minX();
			minZ = box.minZ();
		} else {
			minX = box.minX() + box.getXSpan() / 2 - size / 2;
			minZ = box.minZ() + box.getZSpan() / 2 - size / 2;
		}

		QUEUE.add(new PendingCastle(key, templateId, minX, minZ, Integer.MIN_VALUE, 0));
		QUEUED.add(key);
		Warfront.LOGGER.info("CASTLE_QUEUED {} at {},{} ({} blocks wide, start box {}, pasting in slices)",
				structureId, minX, minZ, size, box);
	}

	/** Advances the head castle: resolve its paste height first, then paste one slice. */
	private static void buildOneSlice(ServerLevel level) {
		PendingCastle pending = QUEUE.peek();
		if (pending == null) return;

		StructureTemplate template;
		try {
			Optional<StructureTemplate> loaded = level.getServer().getStructureManager().get(pending.template());
			if (loaded.isEmpty()) {
				Warfront.LOGGER.error("CASTLE_FAILED template {} is missing; dropping site {}",
						pending.template(), pending.key());
				QUEUE.poll();
				QUEUED.remove(pending.key());
				return;
			}
			template = loaded.get();
		} catch (Exception error) {
			Warfront.LOGGER.error("CASTLE_FAILED loading {}: {}", pending.template(), error.toString());
			QUEUE.poll();
			QUEUED.remove(pending.key());
			return;
		}

		final int width = Math.max(template.getSize().getX(), template.getSize().getZ());

		if (pending.originY() == Integer.MIN_VALUE) {
			resolveGroundHeight(level, pending, width);
			return;
		}

		int fromX = pending.nextX();
		int toX = Math.min(width - 1, fromX + SLICE_BLOCKS - 1);
		BoundingBox slice = new BoundingBox(
				pending.minX() + fromX, level.getMinY(), pending.minZ(),
				pending.minX() + toX, level.getMaxY(), pending.minZ() + width - 1);

		// Load the chunks this slice needs, a few per tick, and only paste once they are all
		// in. getChunk generates a chunk that does not exist yet, so loading a whole strip in
		// one tick is what put the live server 14 seconds behind on the first castle. The
		// blocks still cannot be written before the chunks exist — a slice written into
		// unloaded chunks reports success and leaves nothing behind — so this waits rather
		// than skipping.
		if (!forceChunks(level, slice.minX() >> 4, slice.maxX() >> 4, slice.minZ() >> 4, slice.maxZ() >> 4)) {
			return; // come back next tick; the slice keeps its place in the queue
		}

		// Site preparation, the way a player would do it: everything above the paste height
		// in this slice's columns is cleared, so a hillside never pokes through a courtyard.
		// The template carries no air records (the importer skips air), so the paste alone
		// cannot remove terrain.
		carveAboveOrigin(level, slice, pending.originY());

		// Bounded to one slice. Pasting a whole castle in a single tick freezes a live
		// server for seconds, so it goes down a strip at a time.
		StructurePlaceSettings settings = new StructurePlaceSettings()
				.setBoundingBox(slice)
				.setIgnoreEntities(false);
		boolean placed = template.placeInWorld(level, pending.origin(), pending.origin(), settings,
				level.getRandom(), 2);
		if (!placed) {
			Warfront.LOGGER.warn("CASTLE_SLICE {} slice {}..{} reported no placement", pending.template(),
					fromX, toX);
		}

		// Ground the slice: where the template's underside hangs over a dip in the real
		// terrain, drop a foundation column to solid ground. This is what keeps a castle on
		// a hillside from floating — one sampled paste height can never fit every column.
		groundSlice(level, slice, pending.originY());

		if (toX >= width - 1) {
			QUEUE.poll();
			QUEUED.remove(pending.key());
			// Release the tickets: they exist to hold the ground still while the castle goes
			// down, not to keep a thousand chunks loaded for the rest of the world's life.
			for (int cx = pending.minX() >> 4; cx <= (pending.minX() + width) >> 4; cx++) {
				for (int cz = pending.minZ() >> 4; cz <= (pending.minZ() + width) >> 4; cz++) {
					level.setChunkForced(cx, cz, false);
				}
			}
			CastleSites.get(level.getServer()).markBuilt(pending.key(), pending.origin());
			// Count the castle's own chests back out of the world. filterBlocks hands back
			// TEMPLATE-RELATIVE positions no matter what BlockPos it is given, so the origin
			// has to be added — reading its raw coordinates checks a spot thousands of blocks
			// away, which is how a working paste read as a broken one three times over.
			int expected = 0;
			int present = 0;
			for (var info : template.filterBlocks(pending.origin(), new StructurePlaceSettings(),
					Blocks.CHEST, false)) {
				expected++;
				if (level.getBlockState(pending.origin().offset(info.pos())).is(Blocks.CHEST)) present++;
			}
			Warfront.LOGGER.info("CASTLE_BUILT {} at {} — {}/{} chests verified in world, {} sites built",
					pending.template(), pending.origin().toShortString(), present, expected,
					CastleSites.get(level.getServer()).count());
			if (expected > 0 && present < expected) {
				Warfront.LOGGER.error("CASTLE_INCOMPLETE {} placed only {}/{} of its chests",
						pending.template(), present, expected);
			}
			BaseManager.onCastleBuilt(level, pending.key(), pending.template(), pending.origin(), width);
		} else {
			QUEUE.poll();
			QUEUE.addFirst(new PendingCastle(pending.key(), pending.template(), pending.minX(),
					pending.minZ(), pending.originY(), toX + 1));
		}
	}

	/**
	 * Resolves the paste height from the terrain the castle will actually stand on: the
	 * median ground height of a {@value #GROUND_SAMPLES}x{@value #GROUND_SAMPLES} grid
	 * across the footprint. The old single-column read at the anchor meant one treetop or
	 * one gully decided the height of half a million blocks. Median rather than minimum so
	 * the carve above and the foundations below each handle roughly half the terrain error.
	 *
	 * <p>Budgeted like the slices: the sample chunks are forced a few per tick, and the
	 * heights are only read once every sample chunk is really loaded.
	 */
	private static void resolveGroundHeight(ServerLevel level, PendingCastle pending, int width) {
		int step = Math.max(1, (width - 1) / (GROUND_SAMPLES - 1));
		List<int[]> columns = new ArrayList<>();
		for (int sx = 0; sx < GROUND_SAMPLES; sx++) {
			for (int sz = 0; sz < GROUND_SAMPLES; sz++) {
				columns.add(new int[] { pending.minX() + Math.min(width - 1, sx * step),
						pending.minZ() + Math.min(width - 1, sz * step) });
			}
		}
		int loadedThisTick = 0;
		boolean ready = true;
		for (int[] column : columns) {
			int cx = column[0] >> 4;
			int cz = column[1] >> 4;
			if (level.getChunkSource().hasChunk(cx, cz)) continue;
			if (loadedThisTick >= CHUNKS_PER_TICK) { ready = false; break; }
			level.setChunkForced(cx, cz, true);
			loadedThisTick++;
		}
		if (!ready) return;

		List<Integer> heights = new ArrayList<>(columns.size());
		for (int[] column : columns) {
			// OCEAN_FLOOR: the ground itself — under the trees and under the water.
			heights.add(level.getHeight(Heightmap.Types.OCEAN_FLOOR, column[0], column[1]));
		}
		heights.sort(Integer::compareTo);
		int median = heights.get(heights.size() / 2);
		QUEUE.poll();
		QUEUE.addFirst(new PendingCastle(pending.key(), pending.template(), pending.minX(),
				pending.minZ(), median - 1, 0));
		Warfront.LOGGER.info("CASTLE_GROUND {} paste height {} (sampled {}..{} across the footprint)",
				pending.key(), median - 1, heights.get(0), heights.get(heights.size() - 1));
	}

	/** Forces the chunks of one region a few per tick; true once they are all present. */
	private static boolean forceChunks(ServerLevel level, int minCx, int maxCx, int minCz, int maxCz) {
		int loadedThisTick = 0;
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				if (level.getChunkSource().hasChunk(cx, cz)) continue;
				if (loadedThisTick >= CHUNKS_PER_TICK) return false;
				// setChunkForced, not getChunk: a chunk pulled in without a ticket is
				// unloaded again before the next tick, so a budgeted loop over getChunk
				// re-loads the same chunks forever and the castle never finishes.
				level.setChunkForced(cx, cz, true);
				loadedThisTick++;
			}
		}
		return true;
	}

	/** Clears natural terrain above the paste height in this slice's columns. */
	private static void carveAboveOrigin(ServerLevel level, BoundingBox slice, int originY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = slice.minX(); x <= slice.maxX(); x++) {
			for (int z = slice.minZ(); z <= slice.maxZ(); z++) {
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				for (int y = top; y > originY; y--) {
					pos.set(x, y, z);
					if (!level.getBlockState(pos).isAir()) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
					}
				}
			}
		}
	}

	/**
	 * Extends the template's underside down to solid ground, column by column. The fill
	 * copies the underside block itself (grass becomes dirt), so a stone terrace grows
	 * stone footings rather than a visibly alien plug.
	 */
	private static void groundSlice(ServerLevel level, BoundingBox slice, int originY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = slice.minX(); x <= slice.maxX(); x++) {
			for (int z = slice.minZ(); z <= slice.maxZ(); z++) {
				int underside = Integer.MIN_VALUE;
				for (int y = originY; y <= originY + FOUNDATION_SEARCH_HEIGHT; y++) {
					pos.set(x, y, z);
					if (!level.getBlockState(pos).isAir()) {
						underside = y;
						break;
					}
				}
				if (underside == Integer.MIN_VALUE) continue;
				pos.set(x, underside, z);
				BlockState fill = level.getBlockState(pos);
				if (fill.is(Blocks.GRASS_BLOCK) || fill.is(Blocks.DIRT_PATH) || fill.is(Blocks.FARMLAND)) {
					fill = Blocks.DIRT.defaultBlockState();
				}
				for (int y = underside - 1; y >= Math.max(level.getMinY(), underside - FOUNDATION_MAX_DEPTH); y--) {
					pos.set(x, y, z);
					BlockState below = level.getBlockState(pos);
					if (!below.isAir() && below.getFluidState().isEmpty() && !below.canBeReplaced()) {
						break; // reached real terrain
					}
					level.setBlock(pos, fill, 2);
				}
			}
		}
	}
}
