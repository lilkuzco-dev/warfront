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
