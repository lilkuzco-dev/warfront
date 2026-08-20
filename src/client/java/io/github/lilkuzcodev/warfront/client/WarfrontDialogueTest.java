package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Dialogue UI verification (Stage 6 #8/#9 visuals): right-click opens the screen with
 * four options + Leave, "More..." rerolls once, and a player with soldier blood on
 * their hands opens the same conversation in a negative band (different greeting
 * flavor, different option tree). Screenshots land in build/run-gametest/screenshots/.
 */
public class WarfrontDialogueTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		// The worldgen check needs a normal world and this test builds a flat one; skip so the
		// two do not fight over the same run.
		if (Boolean.getBoolean("warfront.worldgen.only")) return;
		if (Boolean.getBoolean("warfront.c2.only")) return;
		int originalGuiScale = context.computeOnClient(client -> client.options.guiScale().get());
		System.setProperty("warfront.test.dialogueBranch", "prototype_defensive_barrier");
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
			Set<String> initialOptions = context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).optionIdsForTest());
			if (!context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).hasRequiredTonesForTest())) {
				throw new AssertionError("Dialogue did not offer positive, neutral, negative, and exit tones");
			}
			if (!context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).allOptionTextFitsForTest())) {
				throw new AssertionError("Dialogue option text does not fit its dynamically sized button");
			}
			// Turn AI back on and try to force a path while the screen is open. Dialogue
			// must own movement and keep the soldier beside/facing the player.
			double[] heldPosition = server.computeOnServer(minecraftServer -> {
				SoldierEntity soldier = minecraftServer.overworld().getEntitiesOfClass(SoldierEntity.class,
						minecraftServer.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(8)).getFirst();
				return new double[] { soldier.getX(), soldier.getY(), soldier.getZ() };
			});
			server.runOnServer(minecraftServer -> {
				SoldierEntity soldier = minecraftServer.overworld().getEntitiesOfClass(SoldierEntity.class,
						minecraftServer.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(8)).getFirst();
				soldier.setNoAi(false);
				soldier.getNavigation().moveTo(soldier.getX() + 6, soldier.getY(), soldier.getZ(), 1.0);
			});
			context.waitTicks(25);
			server.runOnServer(minecraftServer -> {
				SoldierEntity soldier = minecraftServer.overworld().getEntitiesOfClass(SoldierEntity.class,
						minecraftServer.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(8)).getFirst();
				double dx = soldier.getX() - heldPosition[0];
				double dy = soldier.getY() - heldPosition[1];
				double dz = soldier.getZ() - heldPosition[2];
				if (soldier.getDialoguePartner() == null || !soldier.getNavigation().isDone()
						|| dx * dx + dy * dy + dz * dz > 0.000001) {
					throw new AssertionError("Soldier did not hold position during dialogue");
				}
			});
			String conversationalOption = context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).firstConversationalOptionLabelForTest());
			context.clickScreenButton(conversationalOption);
			context.waitFor(client -> client.gui.screen() instanceof DialogueScreen screen
					&& screen.hasVisibleExchangeForTest());
			server.runOnServer(minecraftServer -> {
				var player = minecraftServer.getPlayerList().getPlayers().getFirst();
				var state = io.github.lilkuzcodev.warfront.data.WarfrontState.get(minecraftServer);
				long now = io.github.lilkuzcodev.warfront.data.WarfrontState.clock(player.level());
				if (state.standing(player.getUUID(), "vostok") < 1
						|| !state.remembers(player.getUUID(), "vostok", "friendly_words", now)) {
					throw new AssertionError("Friendly dialogue did not improve standing and disposition");
				}
			});
			context.takeScreenshot("dialogue_response");
			if (context.tryClickScreenButton("More...")) {
				context.waitTicks(10);
				context.takeScreenshot("dialogue_rerolled");
			}
			context.clickScreenButton("Leave");
			context.waitTicks(5);
			server.runOnServer(minecraftServer -> {
				SoldierEntity soldier = minecraftServer.overworld().getEntitiesOfClass(SoldierEntity.class,
						minecraftServer.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(8)).getFirst();
				if (soldier.getDialoguePartner() != null) {
					throw new AssertionError("Soldier kept dialogue focus after Leave");
				}
				soldier.setNoAi(true);
			});
			System.setProperty("warfront.test.dialogueBranch", "contested_river_bridge");
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(DialogueScreen.class);
			Set<String> reopenedOptions = context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).optionIdsForTest());
			Set<String> repeated = new HashSet<>(initialOptions);
			repeated.retainAll(reopenedOptions);
			if (!repeated.isEmpty()) {
				throw new AssertionError("Reopened dialogue repeated recent options: " + repeated);
			}
			context.takeScreenshot("dialogue_reopened_fresh");

			// Enter the guaranteed neutral long-form topic and follow it several layers.
			String deepEntry = context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).firstOptionLabelForToneForTest("neutral"));
			context.clickScreenButton(deepEntry);
			context.waitFor(client -> client.gui.screen() instanceof DialogueScreen screen
					&& screen.inDeepBranchForTest());
			if (!context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).allOptionTextFitsForTest())) {
				throw new AssertionError("Deep dialogue option text does not fit its dynamically sized button");
			}
			context.takeScreenshot("dialogue_deep_branch_2");
			for (int expectedDepth = 3; expectedDepth <= 5; expectedDepth++) {
				String neutralFollowup = context.computeOnClient(client ->
						((DialogueScreen) client.gui.screen()).firstOptionLabelForToneForTest("neutral"));
				context.clickScreenButton(neutralFollowup);
				int targetDepth = expectedDepth;
				context.waitFor(client -> client.gui.screen() instanceof DialogueScreen screen
						&& screen.branchDepthForTest() == targetDepth);
			}
			context.takeScreenshot("dialogue_deep_branch_5");
			context.clickScreenButton("Change Subject");
			context.waitFor(client -> client.gui.screen() instanceof DialogueScreen screen
					&& screen.atTopicRootForTest());
			System.clearProperty("warfront.test.dialogueBranch");
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
			context.waitTicks(10);

			// Custom soldiers and vanilla Enemy mobs must acquire each other on sight.
			server.runCommand("kill @e[type=warfront:soldier]");
			server.runCommand("execute at @p run summon warfront:soldier ~ ~ ~4 {warfront_faction:\"aegis\"}");
			server.runCommand("execute at @p run summon minecraft:husk ~2 ~ ~4 {PersistenceRequired:1b}");
			context.waitTicks(40);
			server.runOnServer(minecraftServer -> {
				var box = minecraftServer.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(12);
				SoldierEntity soldier = minecraftServer.overworld().getEntitiesOfClass(SoldierEntity.class, box).getFirst();
				java.util.List<Mob> enemies = minecraftServer.overworld().getEntitiesOfClass(Mob.class, box,
						mob -> mob instanceof Enemy);
				if (!(soldier.getTarget() instanceof Enemy)
						|| enemies.stream().noneMatch(enemy -> enemy.getTarget() == soldier)) {
					throw new AssertionError("Soldier and hostile mob did not target each other on sight; soldierTarget="
							+ soldier.getTarget() + ", enemies=" + enemies.stream()
									.map(enemy -> enemy + "->" + enemy.getTarget()).toList());
				}
				java.util.UUID playerId = minecraftServer.getPlayerList().getPlayers().getFirst().getUUID();
				int limit = soldier.dialogueTemperLimit();
				for (int i = 1; i < limit; i++) {
					if (soldier.reactToDialogue(playerId, "negative")) {
						throw new AssertionError("Soldier attacked before its individual temper limit");
					}
				}
				if (soldier.reactToDialogue(playerId, "positive")) {
					throw new AssertionError("Friendly dialogue triggered aggression");
				}
				if (soldier.reactToDialogue(playerId, "negative")) {
					throw new AssertionError("Friendly dialogue did not cool the soldier's temper");
				}
				if (!soldier.reactToDialogue(playerId, "negative")) {
					throw new AssertionError("Soldier did not attack at its individual temper limit");
				}
			});
		}
		for (String faction : new String[] { "vostok", "aegis", "sarab" }) {
			captureBarrierLore(context, faction);
		}
		context.runOnClient(client -> {
			client.options.guiScale().set(originalGuiScale);
			client.resizeGui();
		});
	}

	private static void captureBarrierLore(ClientGameTestContext context, String faction) {
		System.setProperty("warfront.test.dialogueBranch", "prototype_defensive_barrier");
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(80);
			var server = world.getServer();
			server.runCommand("time set noon");
			server.runCommand("gamemode creative @p");
			server.runCommand("execute at @p run fill ~-6 ~-1 ~-2 ~6 ~-1 ~8 minecraft:smooth_stone");
			server.runCommand("execute at @p run fill ~-6 ~ ~-2 ~6 ~4 ~8 minecraft:air");
			server.runCommand("execute at @p run tp @p ~ ~ ~ 0 12");
			server.runCommand("execute at @p run summon warfront:soldier ~ ~ ~2.5 {warfront_faction:\""
					+ faction + "\",warfront_rank:\"officer\",NoAI:1b,Rotation:[180f,0f]}");
			server.runOnServer(minecraftServer -> {
				var player = minecraftServer.getPlayerList().getPlayers().getFirst();
				io.github.lilkuzcodev.warfront.data.WarfrontState.get(minecraftServer)
						.addStanding(player.getUUID(), faction, 25);
			});
			context.waitTicks(40);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(DialogueScreen.class);
			String barrierEntry = context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).firstOptionLabelForToneForTest("neutral"));
			context.clickScreenButton(barrierEntry);
			context.waitFor(client -> client.gui.screen() instanceof DialogueScreen screen
					&& screen.inDeepBranchForTest() && screen.isPrototypeBarrierTopicForTest());
			for (int expectedDepth = 3; expectedDepth <= 6; expectedDepth++) {
				String followup = context.computeOnClient(client ->
						((DialogueScreen) client.gui.screen()).firstOptionLabelForToneForTest("neutral"));
				context.clickScreenButton(followup);
				int targetDepth = expectedDepth;
				context.waitFor(client -> client.gui.screen() instanceof DialogueScreen screen
						&& screen.branchDepthForTest() == targetDepth);
			}
			String historyQuestion = context.computeOnClient(client ->
					((DialogueScreen) client.gui.screen()).firstOptionLabelForToneForTest("positive"));
			context.clickScreenButton(historyQuestion);
			context.waitFor(client -> client.gui.screen() instanceof DialogueScreen screen
					&& screen.branchDepthForTest() == 7 && screen.hasVisibleExchangeForTest());
			context.runOnClient(client -> {
				DialogueScreen screen = (DialogueScreen) client.gui.screen();
				String reply = screen.latestReplyForTest();
				String required = switch (faction) {
					case "vostok" -> "don't call it cover";
					case "aegis" -> "checklist said advance";
					default -> "operators we left behind did not";
				};
				if (!reply.contains(required)) {
					throw new AssertionError(faction + " barrier answer lacked faction-specific history: " + reply);
				}
				if (Math.round(screen.standingValueForTest()) != 26) {
					throw new AssertionError("Reference barrier conversation was not at friendly standing");
				}
			});
			int[] effectiveWidths = new int[2];
			int scaleIndex = 0;
			for (int guiScale : new int[] { 1, 2 }) {
				context.runOnClient(client -> {
					client.options.guiScale().set(guiScale);
					client.resizeGui();
				});
				context.waitTicks(10);
				context.runOnClient(client -> {
					DialogueScreen screen = (DialogueScreen) client.gui.screen();
					if (!screen.headerRowsDoNotOverlapForTest()) {
						throw new AssertionError("Dialogue header overlaps at GUI scale " + guiScale);
					}
					if (!screen.replyUsesPaddedFullWidthForTest() || !screen.allOptionTextFitsForTest()
							|| !screen.optionLabelsExposeNoToneMarkersForTest()) {
						throw new AssertionError("Dialogue wrapping failed at GUI scale " + guiScale);
					}
					if (!screen.referenceExchangeFitsWithoutScrollForTest()) {
						throw new AssertionError("Reference player question and reply do not fit at GUI scale " + guiScale);
					}
				});
				effectiveWidths[scaleIndex++] = context.computeOnClient(client ->
						((DialogueScreen) client.gui.screen()).screenWidthForTest());
				context.takeScreenshot("dialogue_barrier_" + faction + "_gui" + guiScale);
			}
			if (effectiveWidths[0] == effectiveWidths[1]) {
				throw new AssertionError("GUI scale battery did not produce two independent layouts");
			}
			context.clickScreenButton("Leave");
			context.waitTicks(10);
		} finally {
			System.clearProperty("warfront.test.dialogueBranch");
		}
	}
}
