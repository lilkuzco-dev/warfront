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
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(80);
			var server = world.getServer();
			server.runCommand("time set noon");
			server.runCommand("gamemode creative @p");

			// --- soldier lineup: 3 factions x soldier/officer, arms/overlays in-camera ---
			server.runCommand("execute at @p run fill ~-8 ~-1 ~-2 ~8 ~-1 ~10 minecraft:smooth_stone");
			server.runCommand("execute at @p run fill ~-8 ~ ~-2 ~8 ~6 ~10 minecraft:air");
			server.runCommand("execute at @p run tp @p ~ ~ ~ 0 10");
			server.runCommand("execute at @p run summon warfront:soldier ~-5 ~ ~6 {warfront_faction:\"vostok\",NoAI:1b}");
			server.runCommand("execute at @p run summon warfront:soldier ~-3 ~ ~6 {warfront_faction:\"vostok\",warfront_rank:\"officer\",NoAI:1b}");
			server.runCommand("execute at @p run summon warfront:soldier ~-0.5 ~ ~6 {warfront_faction:\"aegis\",NoAI:1b}");
			server.runCommand("execute at @p run summon warfront:soldier ~1.5 ~ ~6 {warfront_faction:\"aegis\",warfront_rank:\"officer\",NoAI:1b}");
			server.runCommand("execute at @p run summon warfront:soldier ~4 ~ ~6 {warfront_faction:\"sarab\",NoAI:1b}");
			server.runCommand("execute at @p run summon warfront:soldier ~6 ~ ~6 {warfront_faction:\"sarab\",warfront_rank:\"officer\",NoAI:1b}");
			context.waitTicks(60);
			context.takeScreenshot("soldier_lineup");
			server.runCommand("kill @e[type=warfront:soldier]");

			// --- retheme triptych: the same sourced barracks in three faction skins ---
			server.runCommand("execute at @p run fill ~-30 ~-1 ~2 ~30 ~-1 ~30 minecraft:smooth_stone");
			server.runCommand("execute at @p run fill ~-30 ~ ~2 ~30 ~24 ~30 minecraft:air");
			server.runCommand("execute at @p run place template warfront:vostok/barracks_1 ~-24 ~ ~8");
			server.runCommand("execute at @p run place template warfront:aegis/barracks_1 ~-6 ~ ~8");
			server.runCommand("execute at @p run place template warfront:sarab/barracks_1 ~10 ~ ~8");
			server.runCommand("execute at @p run tp @p ~ ~8 ~ 0 25");
			context.waitTicks(40);
			context.takeScreenshot("retheme_triptych");

			// --- all nine tier x faction bases (aerial shots) ---
			String[] factions = { "vostok", "aegis", "sarab" };
			String[] outpostIds = { "vostok_base", "aegis_base", "sarab_outpost" };
			int x = 300;
			for (int i = 0; i < 3; i++) {
				shootStructure(context, world, "warfront:" + outpostIds[i], factions[i] + "_outpost", x, 40);
				x += 300;
				shootStructure(context, world, "warfront:" + factions[i] + "_forward_base", factions[i] + "_forward_base", x, 60);
				x += 300;
				shootStructure(context, world, "warfront:" + factions[i] + "_headquarters", factions[i] + "_headquarters", x, 90);
				x += 300;
			}
		}
	}

	private void shootStructure(ClientGameTestContext context, TestSingleplayerContext world,
			String structureId, String shotName, int x, int height) {
		var server = world.getServer();
		server.runCommand("tp @p " + x + " 140 0");
		context.waitTicks(40);
		server.runCommand("place structure " + structureId + " " + x + " -60 0");
		context.waitTicks(30);
		// hover off the south-west corner looking down onto the compound
		server.runCommand("execute positioned " + x + " 0 0 run tp @p ~-30 " + (80 + height) + " ~-30 45 55");
		context.waitTicks(40);
		context.takeScreenshot(shotName);
		server.runCommand("kill @e[type=warfront:soldier]");
	}
}
