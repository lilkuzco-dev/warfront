package io.github.lilkuzcodev.warfront.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * In-camera verification (Stage 6): soldier lineup for all three factions (settles the
 * open Phase 1 render item + validates the Stage 5 ghost-limb fix), the retheme
 * triptych (one sourced barracks in three faction skins), and all nine tier×faction
 * bases. Screenshots land in build/run-gametest/screenshots/.
 * Runs only under ./gradlew runGametest — never in normal play.
 */
public class WarfrontRenderTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		// before world creation: the integrated server snapshots the client view
		// distance at connect; the 130-block aerial cameras need the full radius
		context.runOnClient(client -> client.options.renderDistance().set(32));
		try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(ui -> {
			// flat world: deterministic surface height for the aerial shots
			for (var entry : ui.getNormalPresetList()) {
				if (entry.preset().unwrapKey().map(k -> k.identifier().toString().equals("minecraft:flat")).orElse(false)) {
					ui.setWorldType(entry);
					break;
				}
			}
		}).create()) {
			context.waitTicks(80);
			var server = world.getServer();
			server.runCommand("time set noon");
			server.runCommand("gamerule mob_spawning false");
			server.runCommand("gamerule doMobSpawning false");
			server.runCommand("gamemode creative @p");

			// --- soldier lineup: 3 factions x soldier/officer, arms/overlays in-camera ---
			server.runCommand("execute at @p run fill ~-8 ~-1 ~-2 ~8 ~-1 ~10 minecraft:smooth_stone");
			server.runCommand("execute at @p run fill ~-8 ~ ~-2 ~8 ~6 ~10 minecraft:air");
			server.runCommand("execute at @p run tp @p ~ ~ ~ 0 10");
			server.runCommand("execute at @p run summon warfront:soldier ~-5 ~ ~6 {warfront_faction:\"vostok\",NoAI:1b,Rotation:[180f,0f]}");
			server.runCommand("execute at @p run summon warfront:soldier ~-3 ~ ~6 {warfront_faction:\"vostok\",warfront_rank:\"officer\",NoAI:1b,Rotation:[180f,0f]}");
			server.runCommand("execute at @p run summon warfront:soldier ~-0.5 ~ ~6 {warfront_faction:\"aegis\",NoAI:1b,Rotation:[180f,0f]}");
			server.runCommand("execute at @p run summon warfront:soldier ~1.5 ~ ~6 {warfront_faction:\"aegis\",warfront_rank:\"officer\",NoAI:1b,Rotation:[180f,0f]}");
			server.runCommand("execute at @p run summon warfront:soldier ~4 ~ ~6 {warfront_faction:\"sarab\",NoAI:1b,Rotation:[180f,0f]}");
			server.runCommand("execute at @p run summon warfront:soldier ~6 ~ ~6 {warfront_faction:\"sarab\",warfront_rank:\"officer\",NoAI:1b,Rotation:[180f,0f]}");
			context.waitTicks(60);
			context.takeScreenshot("soldier_lineup");
			server.runCommand("kill @e[type=warfront:soldier]");

			// --- retheme triptych: the same sourced barracks in three faction skins ---
			server.runCommand("execute at @p run fill ~-30 ~-1 ~2 ~30 ~-1 ~30 minecraft:smooth_stone");
			server.runCommand("execute at @p run fill ~-30 ~ ~2 ~30 ~11 ~30 minecraft:air");
			server.runCommand("execute at @p run fill ~-30 ~12 ~2 ~30 ~24 ~30 minecraft:air");
			server.runCommand("execute at @p run place template warfront:vostok/barracks_1 ~-24 ~ ~8");
			server.runCommand("execute at @p run place template warfront:aegis/barracks_1 ~-6 ~ ~8");
			server.runCommand("execute at @p run place template warfront:sarab/barracks_1 ~10 ~ ~8");
			// spectator from here on: every remaining camera is an elevated tp, and a
			// creative player falls out of position during the chunk-render wait
			server.runCommand("gamemode spectator @p");
			server.runCommand("execute at @p run tp @p ~ ~16 ~-26 0 22");
			context.waitTicks(80);
			context.takeScreenshot("retheme_triptych");

			// --- all nine tier x faction bases (aerial shots) ---
			String[] factions = { "vostok", "aegis", "sarab" };
			String[] outpostIds = { "vostok_base", "aegis_base", "sarab_outpost" };
			int x = 300;
			for (int i = 0; i < 3; i++) {
				shootStructure(context, world, "warfront:" + outpostIds[i], factions[i] + "_outpost", x, 60);
				x += 300;
				shootStructure(context, world, "warfront:" + factions[i] + "_forward_base", factions[i] + "_forward_base", x, 90);
				x += 300;
				shootStructure(context, world, "warfront:" + factions[i] + "_headquarters", factions[i] + "_headquarters", x, 130);
				x += 300;
			}

			// --- aegis HQ eye-level read (depth-ruling addendum): does the plate read
			// as a genuine installation from the ground, not just from the air?
			// Unrotated /place template so the generator's composition coordinates hold
			// (gate south, command post west column, QM east, towers north corners).
			int ex = 3600;
			server.runCommand("forceload add " + (ex - 96) + " -96 " + (ex + 176) + " 176");
			server.runCommand("tp @p " + ex + " -55 0");
			context.waitTicks(200);
			server.runCommand("place template warfront:aegis/headquarters " + ex + " -60 0");
			context.waitTicks(80);
			// spectator: eye-level cameras must hold position (and clip into doorways)
			server.runCommand("gamemode spectator @p");
			server.runCommand("tp @p " + (ex + 40) + " -58 95 180 5");
			context.waitTicks(60);
			context.takeScreenshot("aegis_hq_eye_gate");
			int[][] courtyardShots = { { 180, 0 }, { -90, 0 }, { 0, 0 }, { 90, 0 } };
			String[] dirNames = { "n", "e", "s", "w" };
			for (int i = 0; i < 4; i++) {
				server.runCommand("tp @p " + (ex + 40) + " -58 40 " + courtyardShots[i][0] + " " + courtyardShots[i][1]);
				context.waitTicks(30);
				context.takeScreenshot("aegis_hq_eye_court_" + dirNames[i]);
			}
			server.runCommand("tp @p " + (ex + 16) + " -58 27 90 0");
			context.waitTicks(30);
			context.takeScreenshot("aegis_hq_eye_command_door");
			server.runCommand("tp @p " + (ex + 8) + " -58 27 90 0");
			context.waitTicks(30);
			context.takeScreenshot("aegis_hq_eye_command_interior");
			server.runCommand("forceload remove all");
			server.runCommand("kill @e[type=warfront:soldier]");
		}
	}

	private void shootStructure(ClientGameTestContext context, TestSingleplayerContext world,
			String structureId, String shotName, int x, int height) {
		var server = world.getServer();
		// the placement clearance check needs the full max_distance_from_center radius
		// loaded, so forceload a 160-block halo (two adds — 256-chunk command limit)
		server.runCommand("forceload add " + (x - 160) + " -160 " + (x + 160) + " -1");
		server.runCommand("forceload add " + (x - 160) + " 0 " + (x + 160) + " 160");
		server.runCommand("tp @p " + x + " 0 0");
		context.waitTicks(200);
		server.runCommand("place structure " + structureId + " " + x + " 0 0");
		context.waitTicks(60);
		// /place rotates the compound into an arbitrary quadrant off the anchor;
		// shoot straight down over all four quadrant centers — one of them frames it.
		// Absolute spectator tp: flat-world surface is -60, so camera y = height - 60.
		int half = height / 2;
		int shot = 1;
		for (int[] q : new int[][] { { half, half }, { -half, half }, { half, -half }, { -half, -half } }) {
			server.runCommand("tp @p " + (x + q[0]) + " " + (height - 60) + " " + q[1] + " -45 90");
			context.waitTicks(60);
			context.takeScreenshot(shotName + "_q" + shot);
			shot++;
		}
		server.runCommand("forceload remove all");
		server.runCommand("kill @e[type=warfront:soldier]");
	}
}
