package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.civilization.CitizenProfession;
import io.github.lilkuzcodev.warfront.entity.CitizenEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
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
		// The worldgen check needs a normal world and this test builds a flat one; skip so the
		// two do not fight over the same run.
		if (Boolean.getBoolean("warfront.worldgen.only")) return;
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
			server.runCommand("gamemode creative @p");
			// Generated bases can hydrate soldiers near flat-world spawn. They add noise to
			// the benchmark and may attack/open dialogue, so isolate the camera first.
			server.runCommand("kill @e[type=warfront:soldier]");
			server.runCommand("kill @e[type=warfront:citizen]");
			if (Boolean.getBoolean("warfront.castle.only")) {
				renderGrandCastle(context, world);
				return;
			}

			// --- C2 Phase 1: one controller texture split across a full 5x3 wall ---
			server.runCommand("execute at @p run fill ~-5 ~-1 ~-2 ~5 ~-1 ~10 minecraft:smooth_stone");
			server.runCommand("execute at @p run fill ~-5 ~ ~-2 ~5 ~5 ~10 minecraft:air");
			server.runCommand("execute at @p run tp @p ~ ~1 ~-1 0 5");
			server.runCommand("gamemode spectator @p");
			context.waitTicks(60);
			PerfSample baseline = samplePerformance(context, 120);
			server.runCommand("execute at @p run fill ~-2 ~ ~6 ~2 ~2 ~6 warfront:screen[facing=north]");
			server.runCommand("execute at @p run setblock ~ ~-1 ~3 warfront:projector");
			context.waitTicks(60);
			PerfSample wall = samplePerformance(context, 120);
			System.out.printf("c2DisplayPerf samples=120 baselineMedianFps=%.1f wallMedianFps=%.1f "
					+ "baselineP95FrameMs=%.3f wallP95FrameMs=%.3f%n",
					baseline.medianFps, wall.medianFps, baseline.p95FrameMs, wall.p95FrameMs);
			if (wall.medianFps < baseline.medianFps * 0.5
					|| wall.p95FrameMs > Math.max(50.0, baseline.p95FrameMs * 2.5)) {
				throw new AssertionError("5x3 display wall caused a material render regression: baseline="
						+ baseline + ", wall=" + wall);
			}
			context.takeScreenshot("c2_5x3_live_wall_and_projector");
			if (Boolean.getBoolean("warfront.c2.only")) {
				return;
			}
			server.runCommand("execute at @p run fill ~-2 ~ ~6 ~2 ~2 ~6 minecraft:air");
			server.runCommand("execute at @p run setblock ~ ~-1 ~3 minecraft:air");
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

			// --- citizens: all five synchronized professions + native merchant UI ---
			server.runCommand("execute at @p run warfront city create render_test aegis 5");
			context.waitTicks(60);
			server.runOnServer(minecraftServer -> {
				var player = minecraftServer.getPlayerList().getPlayers().getFirst();
				var citizens = minecraftServer.overworld().getEntitiesOfClass(CitizenEntity.class,
						player.getBoundingBox().inflate(24));
				citizens.sort(java.util.Comparator.comparingLong(CitizenEntity::serial));
				for (int i = 0; i < citizens.size(); i++) {
					CitizenEntity citizen = citizens.get(i);
					citizen.setNoAi(true);
					citizen.setPos(player.getX() - 4 + i * 2, player.getY(), player.getZ() + 6);
					citizen.setYRot(180.0F);
				}
			});
			context.waitTicks(20);
			assertCitizenSkinsResolve(context);
			context.takeScreenshot("citizen_profession_lineup");
			server.runOnServer(minecraftServer -> {
				var player = minecraftServer.getPlayerList().getPlayers().getFirst();
				var citizens = minecraftServer.overworld().getEntitiesOfClass(CitizenEntity.class,
						player.getBoundingBox().inflate(24));
				CitizenEntity citizen = citizens.stream().min(java.util.Comparator.comparingLong(CitizenEntity::serial))
						.orElseThrow();
				citizen.setPos(player.getX(), player.getY(), player.getZ() + 2.5);
				// Deliberately NOT stocked by hand. Offers now come from the city's conserved
				// economy, so a citizen in a real city has something to sell without a player
				// standing over it while it mines. Hand-stocking here is what hid the fact that
				// trade was impossible in practice — the test passed and the game did not.
			});
			context.waitTicks(5);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(net.minecraft.client.gui.screens.inventory.MerchantScreen.class);
			context.waitTicks(10);
			context.takeScreenshot("citizen_villager_style_market");
			context.getInput().pressKey(options -> options.keyInventory);
			context.waitTicks(5);
			server.runCommand("kill @e[type=warfront:citizen]");

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

			// --- the three faction cities (aerial), plus one eye-level read of the
			// plaza, which is the piece of civilian grammar most likely to be wrong.
			// Settlements measure up to ~230 blocks across once their districts sprawl.
			// The camera looks straight down, so it has to be high enough for the FOV to
			// contain that (~1.4 x altitude); at 120 the town sat in a corner of the frame.
			for (int i = 0; i < 3; i++) {
				shootStructure(context, world, "warfront:" + factions[i] + "_town", factions[i] + "_town", x, 140);
				x += 300;
				shootStructure(context, world, "warfront:" + factions[i] + "_city", factions[i] + "_city", x, 200);
				x += 300;
				shootStructure(context, world, "warfront:" + factions[i] + "_metropolis",
						factions[i] + "_metropolis", x, 260);
				x += 400;
			}

			int cx = 3000;
			server.runCommand("forceload add " + (cx - 96) + " -96 " + (cx + 176) + " 176");
			server.runCommand("tp @p " + cx + " -55 0");
			context.waitTicks(200);
			server.runCommand("place template warfront:aegis/city " + cx + " -60 0");
			context.waitTicks(80);
			// Yaw 0 faces SOUTH (+Z) and 180 faces NORTH (-Z); the plate's plaza sits at
			// z 30..42, its farm plots at z 19..25, so each camera is aimed accordingly.
			server.runCommand("gamemode spectator @p");
			server.runCommand("tp @p " + (cx + 36) + " -56 22 0 5");
			context.waitTicks(60);
			context.takeScreenshot("aegis_city_plaza");
			// look back down the avenue INTO the town, not out at empty superflat
			server.runCommand("tp @p " + (cx + 36) + " -56 62 180 5");
			context.waitTicks(30);
			context.takeScreenshot("aegis_city_avenue");
			// the NW farm plot sits at x 3..11, z 19..25 on the plate
			server.runCommand("tp @p " + (cx + 7) + " -56 32 180 12");
			context.waitTicks(30);
			context.takeScreenshot("aegis_city_farm");

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

	/**
	 * Every citizen profession's skin must point at a texture that exists.
	 *
	 * 0.4.9 shipped with all five pointing at nothing: the skin was built from the full
	 * texture path, but ClientAsset.ResourceTexture takes an asset id and derives
	 * `textures/<path>.png` itself, so the file it actually asked for was
	 * `warfront:textures/textures/entity/citizen/miner.png.png`. Every citizen in every city
	 * rendered as the magenta-and-black missing texture, on a release where every server-side
	 * check was green. A frame caught it; this exists so a frame never has to again.
	 */
	private static void assertCitizenSkinsResolve(ClientGameTestContext context) {
		context.runOnClient(client -> {
			List<String> missing = new ArrayList<>();
			for (CitizenProfession profession : CitizenProfession.values()) {
				Identifier texture = CitizenRenderer.skinFor(profession).body().texturePath();
				if (client.getResourceManager().getResource(texture).isEmpty()) {
					missing.add(profession + " -> " + texture);
				}
			}
			if (!missing.isEmpty()) {
				throw new AssertionError("citizen skins resolve to no texture: " + missing);
			}
			io.github.lilkuzcodev.warfront.Warfront.LOGGER.info("CITIZEN_SKIN_AUDIT {} professions resolve: {}",
					CitizenProfession.values().length,
					java.util.Arrays.stream(CitizenProfession.values())
							.map(p -> p + "=" + CitizenRenderer.skinFor(p).body().texturePath())
							.toList());
		});
	}

	private static PerfSample samplePerformance(ClientGameTestContext context, int samples) {
		double[] fps = new double[samples];
		double[] frameMs = new double[samples];
		for (int i = 0; i < samples; i++) {
			context.waitTicks(1);
			fps[i] = context.computeOnClient(net.minecraft.client.Minecraft::getFps);
			frameMs[i] = context.computeOnClient(client -> client.getFrameTimeNs() / 1_000_000.0);
		}
		java.util.Arrays.sort(fps);
		java.util.Arrays.sort(frameMs);
		return new PerfSample(percentile(fps, 0.50), percentile(frameMs, 0.95));
	}

	private static double percentile(double[] sorted, double percentile) {
		return sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1))];
	}

	private record PerfSample(double medianFps, double p95FrameMs) { }

	/** Dedicated remote-only proof frame for the literal 501-block castle template. */
	private void renderGrandCastle(ClientGameTestContext context, TestSingleplayerContext world) {
		var server = world.getServer();
		server.runCommand("gamerule doMobSpawning false");
		server.runCommand("weather clear");
		server.runCommand("time set 3000");
		// Centre the client before placement so all 501x501 template chunks are loaded
		// by the 32-chunk view distance without exceeding /forceload's 256-chunk cap.
		// Every castle type, one after another, each on its own 1000-block lane so the
		// previous one is far outside view distance. Shooting only Aegis proved only Aegis.
		String[][] castles = {
			{ "warfront:aegis/castle", "aegis" },
			{ "warfront:sarab/castle", "sarab" },
			{ "warfront:vostok/castle", "vostok" },
			{ "warfront:dracula/castle", "dracula" },
		};
		int lane = 0;
		for (String[] castle : castles) {
			// Castles are no longer all 501 wide, so the camera cannot assume a centre.
			// Sarab is 801 and framing it at +250 photographed empty ground.
			final String templateId = castle[0];
			int size = server.computeOnServer(minecraftServer -> minecraftServer.getStructureManager()
					.get(net.minecraft.resources.Identifier.parse(templateId))
					.map(t -> Math.max(t.getSize().getX(), t.getSize().getZ()))
					.orElse(501));
			int originX = lane * 1400;
			int centreX = originX + size / 2;
			// Centre the client before placement so all 501x501 template chunks are loaded
			// by the 32-chunk view distance without exceeding /forceload's 256-chunk cap.
			server.runCommand("gamemode creative @p");
			server.runCommand("tp @p " + centreX + " -55 " + (size / 2));
			// Waits scale with the castle. Fixed waits sized for 501 left an 801-wide,
			// 1.14M-block Sarab photographed as empty sky: placement is synchronous but
			// the client meshes the chunks afterwards, and that takes longer the more there is.
			int settle = 700 + (size - 501) * 2;
			context.waitTicks(settle);
			server.runCommand("place template " + castle[0] + " " + originX + " -60 0");
			context.waitTicks(300 + (size - 501) * 2);
			server.runCommand("kill @e[type=warfront:soldier]");
			server.runCommand("gamemode spectator @p");
			// Fixed altitude: the flat-world surface is -60, and scaling height with the
			// castle put the camera past render distance and photographed empty sky.
			server.runCommand("tp @p " + centreX + " 330 " + (size / 2) + " -45 90");
			context.waitTicks(400 + (size - 501) * 2);
			context.takeScreenshot(castle[1] + "_castle_501_block_aerial");
			// A closer oblique so the architecture reads, not just the silhouette.
			server.runCommand("tp @p " + centreX + " 130 " + (-240) + " 0 30");
			context.waitTicks(120);
			context.takeScreenshot(castle[1] + "_castle_oblique");
			lane++;
		}

		// --- The vampire's veil: per-player midnight, blood moon, snowfall ---
		// Server time is NOON on purpose: the frames prove the veil overrides the real
		// clock for this player alone. The dracula lane's castle is still standing, so
		// the snowfall frame reads against real architecture.
		int draculaCentre = 3 * 1400 + 250;
		server.runCommand("time set noon");
		server.runCommand("warfront veil on @p");
		context.waitTicks(40);
		server.runCommand("tp @p " + draculaCentre + " 40 250 0 -75");
		context.waitTicks(80);
		context.takeScreenshot("vampire_veil_blood_moon");
		server.runCommand("tp @p " + draculaCentre + " 20 100 15 0");
		context.waitTicks(60);
		context.takeScreenshot("vampire_veil_snowfall");
		server.runCommand("warfront veil off @p");
		context.waitTicks(40);
		context.takeScreenshot("vampire_veil_lifted_noon_again");
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
