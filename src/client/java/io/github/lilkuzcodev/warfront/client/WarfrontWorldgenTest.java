package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.Warfront;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Does the running game keep bases out of castles?
 *
 * <p>`tools/verify-base-spacing.js` answers that offline by replaying the placement maths,
 * and it is what caught castles generating in the same chunk as a base. But it is a
 * reimplementation, and `warfront:base_spread` is Warfront's own placement type — the maths
 * being right in JavaScript says nothing about the type being wired into the game correctly.
 * This asks the server's own {@code ChunkGeneratorStructureState}, through the real
 * {@code isStructureChunk} path, and fails the build if a base can land inside a castle.
 *
 * <p>It lives in its own run config rather than in the render battery because that battery
 * uses a <b>flat</b> world for deterministic aerial framing, and a flat world's generator
 * carries only the structure sets its preset lists — Warfront's are not among them, so
 * {@code possibleStructureSets()} comes back without them and the check has nothing to
 * measure. That is not a bug to work around; it is the reason this needs a normal world.
 *
 * <pre>./gradlew runWorldgentest</pre>
 */
public final class WarfrontWorldgenTest implements FabricClientGameTest {

	/** Castles sit one per 160 chunks, so the sweep has to be wide to contain several. */
	private static final int REACH = 700;
	/** Widest castle plate is 501, widest base plate 124: closer than this and one is inside the other. */
	private static final double REQUIRED = 501.0 / 2 + 124.0 / 2;

	@Override
	public void runTest(ClientGameTestContext context) {
		// Fabric runs every declared gametest entrypoint for the modid, so each one gates
		// itself on a flag rather than the run config picking one.
		if (!Boolean.getBoolean("warfront.worldgen.only")) return;
		// The harness's default world is a superflat, whose generator offers only
		// minecraft:strongholds and minecraft:villages — measured. Ask for the normal preset
		// explicitly, or there are no warfront structure sets present to measure at all.
		try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(ui -> {
			for (var entry : ui.getNormalPresetList()) {
				if (entry.preset().unwrapKey()
						.map(key -> key.identifier().toString().equals("minecraft:normal"))
						.orElse(false)) {
					ui.setWorldType(entry);
				}
			}
		}).create()) {
			context.waitTicks(60);
			assertCastlesClearBases(world.getServer());
			assertCastleActuallyBuilds(context, world.getServer());
			assertEveryCastleTypeBuilds(context, world.getServer());
		}
	}

	/**
	 * A castle site must end up with a castle on it.
	 *
	 * <p>This is the check that was missing. The castle render config proved the templates
	 * by pasting them with {@code /place}, which bypasses worldgen entirely — so it stayed
	 * green for weeks while no castle had ever generated in any world. Here the player is
	 * put on a real castle site and the world is asked, afterwards, whether a castle is
	 * standing there.
	 */
	private static void assertCastleActuallyBuilds(ClientGameTestContext context, TestServerContext server) {
		int[] site = server.computeOnServer(minecraftServer -> {
			var level = minecraftServer.overworld();
			var state = level.getChunkSource().getGeneratorState();
			for (var holder : state.possibleStructureSets()) {
				String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
				if (!id.equals("warfront:grand_castles")) continue;
				StructurePlacement placement = holder.value().placement();
				for (int cx = -400; cx <= 400; cx++) {
					for (int cz = -400; cz <= 400; cz++) {
						if (placement.isStructureChunk(state, cx, cz)) return new int[] { cx, cz };
					}
				}
			}
			return new int[] { Integer.MIN_VALUE, 0 };
		});
		if (site[0] == Integer.MIN_VALUE) throw new AssertionError("no castle site within 400 chunks to test");

		int x = site[0] * 16;
		int z = site[1] * 16;
		server.runCommand("gamemode spectator @p");
		server.runCommand("tp @p " + x + " 200 " + z);
		// The builder scans every 100 ticks and then lays the castle down in slices.
		context.waitTicks(900);

		// Check against the TEMPLATE, at the origin the builder actually recorded. Two
		// earlier cuts of this check reported zero for reasons that were both about the
		// check: one looked for stone bricks at a castle made of jungle and snow, the other
		// recomputed the origin from the surface heightmap — which, once a castle is
		// standing, reports the castle's own roof.
		int blocks = server.computeOnServer(minecraftServer -> {
			var level = minecraftServer.overworld();
			var sites = io.github.lilkuzcodev.warfront.worldgen.CastleSites.get(minecraftServer);
			var entry = sites.all().entrySet().stream().findFirst().orElse(null);
			if (entry == null) return -1;
			var origin = sites.origin(entry.getKey());
			if (origin == null) return -1;
			var manager = minecraftServer.getStructureManager();
			int best = 0;
			for (String faction : new String[] { "aegis", "sarab", "vostok", "dracula" }) {
				var loaded = manager.get(io.github.lilkuzcodev.warfront.Warfront.id(faction + "/castle"));
				if (loaded.isEmpty()) continue;
				int hits = 0;
				int probes = 0;
				for (var info : loaded.get().filterBlocks(origin,
						new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
						net.minecraft.world.level.block.Blocks.CHEST, false)) {
					probes++;
					// filterBlocks returns template-relative positions; add the origin.
					if (level.getBlockState(origin.offset(info.pos()))
							.is(net.minecraft.world.level.block.Blocks.CHEST)) hits++;
					if (probes > 600_000) break;
				}
				best = Math.max(best, hits);
			}
			return best;
		});
		if (blocks < 0) throw new AssertionError("the builder recorded no castle site at all");
		Warfront.LOGGER.info("CASTLE_BUILD_CHECK site=({},{}) castle chests present in the world: {}",
				site[0], site[1], blocks);
		if (blocks < 5) {
			throw new AssertionError("castle site (" + site[0] + "," + site[1] + ") matches the template in only "
					+ blocks + " of its chests — the castle did not build");
		}
	}

	/**
	 * Every castle type must paste, not just whichever one the site roll picked. Sarab was
	 * the one that happened to come up naturally, and Sarab is also the one whose import is
	 * known to be wrong — proving it alone would have proved the least useful case.
	 */
	private static void assertEveryCastleTypeBuilds(ClientGameTestContext context, TestServerContext server) {
		String[] factions = { "aegis", "sarab", "vostok", "dracula" };
		for (int i = 0; i < factions.length; i++) {
			final String faction = factions[i];
			final int lane = i;
			server.runOnServer(minecraftServer -> {
				var origin = new net.minecraft.core.BlockPos(20000 + lane * 1000, 64, 20000);
				io.github.lilkuzcodev.warfront.worldgen.CastleBuilder.enqueueForTest(faction, origin);
			});
		}
		// 4 castles x 501 blocks at 48 blocks a tick, plus chunk loading.
		context.waitTicks(1200);

		String verdict = server.computeOnServer(minecraftServer -> {
			var level = minecraftServer.overworld();
			var manager = minecraftServer.getStructureManager();
			StringBuilder report = new StringBuilder();
			int failures = 0;
			for (int i = 0; i < factions.length; i++) {
				var loaded = manager.get(io.github.lilkuzcodev.warfront.Warfront.id(factions[i] + "/castle"));
				if (loaded.isEmpty()) { report.append(factions[i]).append("=NO_TEMPLATE "); failures++; continue; }
				var origin = new net.minecraft.core.BlockPos(20000 + i * 1000, 64, 20000);
				int expected = 0;
				int present = 0;
				for (var info : loaded.get().filterBlocks(origin,
						new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
						net.minecraft.world.level.block.Blocks.CHEST, false)) {
					expected++;
					if (level.getBlockState(origin.offset(info.pos()))
							.is(net.minecraft.world.level.block.Blocks.CHEST)) present++;
				}
				report.append(factions[i]).append("=").append(present).append("/").append(expected).append(" ");
				if (expected == 0 || present < expected) failures++;
			}
			return failures + "|" + report;
		});
		Warfront.LOGGER.info("CASTLE_ALL_TYPES {}", verdict.substring(verdict.indexOf('|') + 1));

		// Photograph what the builder produced, not what /place produces. The old castle
		// proof pasted a template by command, which is exactly the path that worked while
		// worldgen was silently building nothing.
		server.runCommand("gamerule doMobSpawning false");
		server.runCommand("weather clear");
		server.runCommand("time set 3000");
		for (int i = 0; i < factions.length; i++) {
			// Centre on the castle's real size — a hardcoded +250 put Sarab's camera a
			// quarter of the way in — and give the client time to mesh it.
			final String faction = factions[i];
			int size = server.computeOnServer(minecraftServer -> minecraftServer.getStructureManager()
					.get(io.github.lilkuzcodev.warfront.Warfront.id(faction + "/castle"))
					.map(t -> Math.max(t.getSize().getX(), t.getSize().getZ())).orElse(501));
			int cx = 20000 + i * 1000 + size / 2;
			int cz = 20000 + size / 2;
			server.runCommand("gamemode spectator @p");
			server.runCommand("tp @p " + cx + " 300 " + cz + " -45 90");
			context.waitTicks(400 + (size - 501) * 2);
			context.takeScreenshot(faction + "_castle_as_generated");
		}
        int failures = Integer.parseInt(verdict.substring(0, verdict.indexOf('|')));
		if (failures > 0) {
			throw new AssertionError("castle types that did not fully build: " + failures
					+ " — " + verdict.substring(verdict.indexOf('|') + 1));
		}
	}

	private static void assertCastlesClearBases(TestServerContext server) {
		server.runOnServer(minecraftServer -> {
			var state = minecraftServer.overworld().getChunkSource().getGeneratorState();
			StructurePlacement bases = null;
			StructurePlacement castles = null;
			for (var holder : state.possibleStructureSets()) {
				String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
				if (id.equals("warfront:bases")) bases = holder.value().placement();
				if (id.equals("warfront:grand_castles")) castles = holder.value().placement();
			}
			if (bases == null || castles == null) {
				String present = state.possibleStructureSets().stream()
						.map(h -> h.unwrapKey().map(k -> k.identifier().toString()).orElse("?"))
						.sorted().reduce((a, b) -> a + ", " + b).orElse("(none)");
				throw new AssertionError("warfront:bases and warfront:grand_castles must both be "
						+ "loaded structure sets in this world; found bases=" + bases
						+ " castles=" + castles + ". This world offers: " + present);
			}
			if (!(bases instanceof io.github.lilkuzcodev.warfront.worldgen.BaseSpreadPlacement)) {
				throw new AssertionError("warfront:bases resolved to " + bases.getClass().getName()
						+ ", not BaseSpreadPlacement — the custom placement type is not in use, so "
						+ "nothing is keeping a base out of a castle");
			}

			int castlesSeen = 0;
			double worst = Double.MAX_VALUE;
			String worstAt = "";
			for (int cx = -REACH; cx <= REACH; cx++) {
				for (int cz = -REACH; cz <= REACH; cz++) {
					if (!castles.isStructureChunk(state, cx, cz)) continue;
					castlesSeen++;
					for (int dx = -40; dx <= 40; dx++) {
						for (int dz = -40; dz <= 40; dz++) {
							if (!bases.isStructureChunk(state, cx + dx, cz + dz)) continue;
							double distance = Math.hypot(dx * 16.0, dz * 16.0);
							if (distance < worst) {
								worst = distance;
								worstAt = "castle chunk (" + cx + "," + cz + "), base offset ("
										+ dx + "," + dz + ")";
							}
						}
					}
				}
			}
			if (castlesSeen == 0) {
				throw new AssertionError("no castle placement chunk within " + REACH
						+ " chunks — the sweep proves nothing, so it fails rather than passing");
			}
			Warfront.LOGGER.info("CASTLE_CLEARANCE castles={} closest base {} blocks (need {}) {}",
					castlesSeen, String.format("%.0f", worst), String.format("%.0f", REQUIRED),
					worst == Double.MAX_VALUE ? "no base anywhere near one" : "at " + worstAt);
			if (worst < REQUIRED) {
				throw new AssertionError("a base places " + String.format("%.0f", worst)
						+ " blocks from a castle centre; the widest plates need "
						+ String.format("%.0f", REQUIRED) + " — " + worstAt);
			}
		});
	}
}
