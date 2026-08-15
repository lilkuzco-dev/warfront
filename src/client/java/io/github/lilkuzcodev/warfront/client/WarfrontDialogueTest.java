package io.github.lilkuzcodev.warfront.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Dialogue UI verification (Stage 6 #8/#9 visuals): right-click opens the screen with
 * four options + Leave, "More..." rerolls once, and a player with soldier blood on
 * their hands opens the same conversation in a negative band (different greeting
 * flavor, different option tree). Screenshots land in build/run-gametest/screenshots/.
 */
public class WarfrontDialogueTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(80);
			var server = world.getServer();
			server.runCommand("time set noon");
			server.runCommand("gamemode creative @p");
			server.runCommand("execute at @p run fill ~-6 ~-1 ~-2 ~6 ~-1 ~8 minecraft:smooth_stone");
			server.runCommand("execute at @p run fill ~-6 ~ ~-2 ~6 ~4 ~8 minecraft:air");
			server.runCommand("execute at @p run tp @p ~ ~ ~ 0 12");
			server.runCommand("execute at @p run summon warfront:soldier ~ ~ ~2.5 {warfront_faction:\"vostok\",NoAI:1b,Rotation:[180f,0f]}");
			context.waitTicks(40);

			// neutral-band conversation: 4 options + More + Leave
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(DialogueScreen.class);
			context.waitTicks(10);
			context.takeScreenshot("dialogue_neutral");
			if (context.tryClickScreenButton("More...")) {
				context.waitTicks(10);
				context.takeScreenshot("dialogue_rerolled");
			}
			context.clickScreenButton("Leave");
			context.waitTicks(20);

			// negative bias: inject the Emperor's scenario (3 kills remembered) and re-open
			server.runCommand("execute as @p run warfront ledger vostok killed_soldier");
			server.runCommand("execute as @p run warfront ledger vostok killed_soldier");
			server.runCommand("execute as @p run warfront ledger vostok killed_soldier");
			context.waitTicks(10);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(DialogueScreen.class);
			context.waitTicks(10);
			context.takeScreenshot("dialogue_negative_bias");
			context.clickScreenButton("Leave");
			context.waitTicks(20);

			// quartermaster tree (trade hooks visible)
			server.runCommand("kill @e[type=warfront:soldier]");
			server.runCommand("execute at @p run summon warfront:soldier ~ ~ ~2.5 {warfront_faction:\"aegis\",warfront_rank:\"quartermaster\",NoAI:1b,Rotation:[180f,0f]}");
			context.waitTicks(30);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(DialogueScreen.class);
			context.waitTicks(10);
			context.takeScreenshot("dialogue_quartermaster");
			context.clickScreenButton("Leave");
		}
	}
}
