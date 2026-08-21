package io.github.lilkuzcodev.warfront.worldgen;

import io.github.lilkuzcodev.warfront.Warfront;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Builds Warfront's monumental castles, because Minecraft cannot.
 *
 * <p>A vanilla structure reaches at most <b>128 blocks from its start chunk</b>:
 * {@code ChunkGenerator.createReferences} scans a hardcoded 8-chunk radius for structure
 * starts, and {@code max_distance_from_center} is codec-capped at 128 besides. The castles
 * are <b>501 blocks across</b> — 250 from centre. Measured on the live server on
 * 2026-08-20: {@code /locate} found a castle, the city record was seeded and ticking, and
 * the site held zero castle blocks, zero soldiers and zero citizens. They had never
 * generated, in any world, since the day they were imported.
 *
 * <p>So the castle is not a structure any more. The structure set still exists — that is
 * what {@code /locate} and the base/castle clearance are built on, and its placement maths
 * is deterministic — and this service replays that same placement to find the sites, then
 * pastes the template itself once the ground is loaded.
 *
 * <p><b>Rule 7.</b> This runs on {@code END_SERVER_TICK}, never on an entity or
 * block-entity tick, and every site it has finished is recorded in {@link CastleSites} so
 * the work survives a restart and is never done twice. It is deliberately budgeted: a
 * castle is 2.17 million blocks, and pasting that in one tick would stall the server for
 * seconds, so it goes down in slices with a per-tick cap.
 */
public final class CastleBuilder {

	/** Templates are 501x501; the paste is anchored so the placement chunk is its centre. */
	private static final int CASTLE_SIZE = 501;
	private static final int HALF = CASTLE_SIZE / 2;
	/** How near a player must be before a castle is worth building. */
	private static final int TRIGGER_CHUNKS = 12;
	/** Blocks per tick. A slice is bounded so a 2.17M-block paste never stalls a tick. */
	private static final int SLICE_BLOCKS = 48;
	private static final int SCAN_INTERVAL = 100;

	private static final Deque<PendingCastle> QUEUE = new ArrayDeque<>();
	private static final Set<String> QUEUED = new HashSet<>();

	private record PendingCastle(String key, Identifier template, BlockPos origin, int nextX) {}

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
					if (sites.isBuilt(key) || QUEUED.contains(key)) continue;
					enqueue(level, key, cx, cz);
				}
			}
		}
	}

	/**
	 * Queues one specific castle at one specific place. Exists so a test can prove every
	 * castle type pastes, rather than proving whichever one the site roll happened to pick.
	 */
	public static void enqueueForTest(String faction, BlockPos origin) {
		String key = "test/" + faction + "/" + origin.toShortString();
		QUEUE.add(new PendingCastle(key, Warfront.id(faction + "/castle"), origin, 0));
		QUEUED.add(key);
	}

	/** Picks which castle stands here the same way a weighted structure set would. */
	private static void enqueue(ServerLevel level, String key, int chunkX, int chunkZ) {
		// Deterministic from the chunk, so the same site always yields the same castle no
		// matter which player triggers it or in what order.
		long mix = chunkX * 341873128712L + chunkZ * 132897987541L;
		int roll = Math.floorMod(Long.hashCode(mix), 46);
		String faction = roll == 45 ? "dracula" : roll < 15 ? "aegis" : roll < 30 ? "sarab" : "vostok";

		BlockPos anchor = new BlockPos(chunkX * 16, 0, chunkZ * 16);
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchor.getX(), anchor.getZ());
		BlockPos origin = new BlockPos(anchor.getX() - HALF, surface - 1, anchor.getZ() - HALF);

		QUEUE.add(new PendingCastle(key, Warfront.id(faction + "/castle"), origin, 0));
		QUEUED.add(key);
		Warfront.LOGGER.info("CASTLE_QUEUED {} at {} ({} blocks wide, pasting in slices)",
				faction, origin.toShortString(), CASTLE_SIZE);
	}

	/**
	 * Pastes one bounded slice of the head castle. {@code StructurePlaceSettings} takes a
	 * bounding box, so a slice is a real restriction on what gets written rather than a
	 * whole-template paste we hope is cheap.
	 */
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

		int fromX = pending.nextX();
		int toX = Math.min(CASTLE_SIZE - 1, fromX + SLICE_BLOCKS - 1);
		BoundingBox slice = new BoundingBox(
				pending.origin().getX() + fromX, level.getMinY(), pending.origin().getZ(),
				pending.origin().getX() + toX, level.getMaxY(), pending.origin().getZ() + CASTLE_SIZE - 1);

		// Load every chunk this slice touches, first. placeInWorld reports success whether or
		// not the blocks land: measured, a slice written while chunks were still streaming in
		// returned placed=true and left nothing behind. The paste runs in a handful of ticks
		// and chunk loading does not, so waiting on the player's view distance loses the
		// castle silently — which is the same shape as the bug this whole class exists to fix.
		for (int cx = slice.minX() >> 4; cx <= slice.maxX() >> 4; cx++) {
			for (int cz = slice.minZ() >> 4; cz <= slice.maxZ() >> 4; cz++) {
				level.getChunk(cx, cz);
			}
		}

		// Bounded to one slice. A castle is 2.17 million blocks and pasting it in a single
		// tick freezes a live server for seconds, so it goes down a strip at a time.
		StructurePlaceSettings settings = new StructurePlaceSettings()
				.setBoundingBox(slice)
				.setIgnoreEntities(false);
		boolean placed = template.placeInWorld(level, pending.origin(), pending.origin(), settings,
				level.getRandom(), 2);
		if (!placed) {
			Warfront.LOGGER.warn("CASTLE_SLICE {} slice {}..{} reported no placement", pending.template(),
					fromX, toX);
		}

		if (toX >= CASTLE_SIZE - 1) {
			QUEUE.poll();
			QUEUED.remove(pending.key());
			CastleSites.get(level.getServer()).markBuilt(pending.key(), pending.origin());
			// Count the castle's own chests back out of the world. filterBlocks hands back
			// TEMPLATE-RELATIVE positions no matter what BlockPos it is given, so the origin
			// has to be added — reading its raw coordinates checks a spot thousands of blocks
			// away, which is how a working paste read as a broken one three times over.
			int expected = 0;
			int present = 0;
			for (var info : template.filterBlocks(pending.origin(), new StructurePlaceSettings(),
					net.minecraft.world.level.block.Blocks.CHEST, false)) {
				expected++;
				if (level.getBlockState(pending.origin().offset(info.pos()))
						.is(net.minecraft.world.level.block.Blocks.CHEST)) present++;
			}
			Warfront.LOGGER.info("CASTLE_BUILT {} at {} — {}/{} chests verified in world, {} sites built",
					pending.template(), pending.origin().toShortString(), present, expected,
					CastleSites.get(level.getServer()).count());
			if (expected > 0 && present < expected) {
				Warfront.LOGGER.error("CASTLE_INCOMPLETE {} placed only {}/{} of its chests",
						pending.template(), present, expected);
			}
		} else {
			QUEUE.poll();
			QUEUE.addFirst(new PendingCastle(pending.key(), pending.template(), pending.origin(), toX + 1));
		}
	}
}
