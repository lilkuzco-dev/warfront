package io.github.lilkuzcodev.warfront.client;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.entity.DraculaEntity;
import io.github.lilkuzcodev.warfront.worldgen.CastleBuilder;
import io.github.lilkuzcodev.warfront.worldgen.CastleSites;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * The Count rises. Opt-in ({@code runDraculatest}): builds Dracula's castle through
 * CastleBuilder — the real path, so CastleSites records the site and the veil engages on
 * its own, not by command — sends the baked Count back to his coffin the way the sun
 * does, walks a mortal into the grounds and requires him to rise under a roof.
 *
 * <p>Why a dedicated battery: the veil/castle render lanes place the castle with
 * {@code /place template}, which records no site, so nothing before this ever exercised
 * {@code VampireVeil.ensureDracula}. Its roofed-spot walk returned null on every scan
 * from 0.4.15 through 0.4.17 and no gate noticed, because its every failure was silent.
 */
public class WarfrontDraculaTest implements FabricClientGameTest {

	private static final BlockPos ORIGIN = new BlockPos(20000, 64, 20000);

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!Boolean.getBoolean("warfront.dracula.only")) return;
		context.runOnClient(client -> client.options.renderDistance().set(32));
		try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(ui -> {
			for (var entry : ui.getNormalPresetList()) {
				if (entry.preset().unwrapKey().map(k -> k.identifier().toString().equals("minecraft:flat")).orElse(false)) {
					ui.setWorldType(entry);
					break;
				}
			}
		}).create()) {
			context.waitTicks(80);
			var server = world.getServer();
			server.runCommand("gamerule doMobSpawning false");
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("time set 3000"); // daylight: the sun must not be able to reach him
			server.runCommand("weather clear");
			server.runCommand("gamemode spectator @p");
			int centreX = ORIGIN.getX() + 250;
			int centreZ = ORIGIN.getZ() + 250;
			// Stand over the centre first so the 32-chunk view distance holds the whole
			// 501-block square while the builder pastes — ABOVE the site box (origin y + 96),
			// because a player inside it is a visitor and the Count rises for visitors;
			// the first run of this test raised him two seconds after the /kill below and
			// failed its own "zero after kill" check on the risen one.
			server.runCommand("tp @p " + centreX + " 220 " + centreZ);
			context.waitTicks(200);
			server.runOnServer(minecraftServer -> CastleBuilder.enqueueForTest("dracula", ORIGIN));
			int polls;
			for (polls = 0; polls < 90; polls++) {
				context.waitTicks(200);
				int built = server.computeOnServer(minecraftServer -> CastleSites.get(minecraftServer).count());
				if (built >= 1) break;
			}
			if (polls >= 90) throw new AssertionError("Dracula's castle never reported built");

			// The builder pastes the template's own Count. The sun takes him within a
			// minute of a daylight build; /kill is that death without the wait — a
			// non-player kill credit, so the site is NOT marked slain.
			int baked = countDracula(server);
			Warfront.LOGGER.info("DRACULA_TEST baked Count after build: {}", baked);
			server.runCommand("kill @e[type=warfront:dracula]");
			context.waitTicks(40);
			if (countDracula(server) != 0) throw new AssertionError("the baked Count survived /kill: " + countDracula(server));
			boolean slain = server.computeOnServer(minecraftServer -> {
				CastleSites sites = CastleSites.get(minecraftServer);
				return sites.all().keySet().stream().anyMatch(sites::isDraculaSlain);
			});
			if (slain) throw new AssertionError("a non-player death marked the site slain");

			// A mortal enters. The veil must engage from the recorded site, and the Count
			// must rise within a few scans (one scan per 10 ticks).
			BlockPos origin = server.computeOnServer(minecraftServer -> {
				CastleSites sites = CastleSites.get(minecraftServer);
				String key = sites.all().keySet().iterator().next();
				return sites.origin(key);
			});
			if (origin == null) throw new AssertionError("built site has no origin");
			server.runCommand("tp @p " + (origin.getX() + 250) + " " + (origin.getY() + 8) + " " + (origin.getZ() + 250));
			BlockPos risen = null;
			for (int i = 0; i < 20 && risen == null; i++) {
				context.waitTicks(20);
				risen = server.computeOnServer(minecraftServer -> {
					var level = minecraftServer.overworld();
					var found = level.getEntitiesOfClass(DraculaEntity.class,
							new AABB(origin.getX(), origin.getY() - 16, origin.getZ(),
									origin.getX() + 501, origin.getY() + 96, origin.getZ() + 501));
					return found.isEmpty() ? null : found.getFirst().blockPosition();
				});
			}
			boolean veiled = context.computeOnClient(client -> VampireVeilClient.isActive());
			if (!veiled) throw new AssertionError("a player inside the built site was not veiled");
			if (risen == null) throw new AssertionError("the Count did not rise for a visitor (see DRACULA_WAITS)");
			final BlockPos at = risen;
			boolean roofed = server.computeOnServer(minecraftServer -> !minecraftServer.overworld().canSeeSky(at));
			if (!roofed) throw new AssertionError("the Count rose under open sky at " + at.toShortString());
			Warfront.LOGGER.info("DRACULA_TEST risen at {} roofed={}", at.toShortString(), roofed);

			// Ten seconds of noon: roofed means the sun cannot reach him.
			float before = server.computeOnServer(minecraftServer -> health(minecraftServer));
			context.waitTicks(200);
			float after = server.computeOnServer(minecraftServer -> health(minecraftServer));
			if (countDracula(server) != 1) throw new AssertionError("Count not exactly one after rise: " + countDracula(server));
			if (after < before) throw new AssertionError("the risen Count is burning: " + before + " -> " + after);

			// His textures must resolve — he shipped as Steve from 0.4.9 to 0.4.19 because
			// the render state's default skin won over getTextureLocation.
			context.runOnClient(client -> {
				for (var texture : new net.minecraft.resources.Identifier[] {
						DraculaRenderer.skin().body().texturePath(), DraculaRenderer.skin().cape().texturePath() }) {
					if (client.getResourceManager().getResource(texture).isEmpty()) {
						throw new AssertionError("Dracula texture resolves to nothing: " + texture);
					}
				}
			});

			// The Count comes to the visitor. The spectator becomes a creative visitor far
			// below his tower (y+8 is the ground floor; he rose at y+68); by night so the
			// step is not constrained to roofed spots. He must arrive within STALK range.
			server.runCommand("time set 18000");
			server.runCommand("gamemode creative @p");
			double visitorX = origin.getX() + 250 + 0.5;
			double visitorZ = origin.getZ() + 250 + 0.5;
			server.runCommand("tp @p " + visitorX + " " + (origin.getY() + 8) + " " + visitorZ);
			double closest = Double.MAX_VALUE;
			for (int i = 0; i < 30; i++) {
				context.waitTicks(10);
				closest = server.computeOnServer(minecraftServer -> {
					var player = minecraftServer.getPlayerList().getPlayers().getFirst();
					double best = Double.MAX_VALUE;
					for (var e : minecraftServer.overworld().getAllEntities()) {
						if (e instanceof DraculaEntity d) best = Math.min(best, d.distanceTo(player));
					}
					return best;
				});
				if (closest <= 16.0) break;
			}
			if (closest > 16.0) throw new AssertionError("the Count did not come for the visitor; nearest " + closest);
			Warfront.LOGGER.info("DRACULA_TEST stalked: Count within {} blocks of the visitor", closest);

			// Photograph him from the visitor's eyes: three blocks off, facing him.
			BlockPos him = server.computeOnServer(minecraftServer -> {
				for (var e : minecraftServer.overworld().getAllEntities()) {
					if (e instanceof DraculaEntity d) return d.blockPosition();
				}
				return null;
			});
			// Night vision: the veil is midnight and the first frame of him was a silhouette;
			// the skin and cape are the things being read, so light them.
			server.runCommand("effect give @p minecraft:night_vision 1000 0 true");
			// Freeze him for the portraits: the step's smoke needs a few seconds to clear and
			// he turns to face a visitor faster than a camera can get behind him.
			server.runCommand("data modify entity @e[type=warfront:dracula,limit=1] NoAI set value 1b");
			// Portraits need room: he stalks to wherever the visitor is, and two runs in a
			// row the back camera landed inside masonry or behind a pillar. Stand him in a
			// scanned 7x3x7 pocket of air on a floor inside the grounds first.
			BlockPos pocket = server.computeOnServer(minecraftServer -> {
				var level = minecraftServer.overworld();
				BlockPos centre = new BlockPos(origin.getX() + 250, origin.getY(), origin.getZ() + 250);
				for (int r = 0; r <= 80; r += 4) {
					for (int dx = -r; dx <= r; dx += 4) {
						for (int dz = -r; dz <= r; dz += 4) {
							if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
							for (int y = centre.getY() + 80; y > centre.getY() - 12; y--) {
								BlockPos p = new BlockPos(centre.getX() + dx, y, centre.getZ() + dz);
								if (!level.getBlockState(p.below()).isSolid()) continue;
								boolean clear = true;
								for (int ox = -3; ox <= 3 && clear; ox++) {
									for (int oz = -3; oz <= 3 && clear; oz++) {
										for (int oy = 0; oy <= 2 && clear; oy++) {
											clear = level.getBlockState(p.offset(ox, oy, oz)).isAir();
										}
									}
								}
								if (clear) return p;
							}
						}
					}
				}
				return null;
			});
			if (pocket == null) throw new AssertionError("no open pocket in the grounds for portraits");
			server.runCommand("tp @e[type=warfront:dracula,limit=1] " + (pocket.getX() + 0.5) + " " + pocket.getY() + " " + (pocket.getZ() + 0.5));
			context.waitTicks(5);
			final BlockPos him2 = pocket;
			int[] axis = server.computeOnServer(minecraftServer -> {
				var level = minecraftServer.overworld();
				int[][] options = { { 0, 1 }, { 0, -1 }, { 1, 0 }, { -1, 0 } };
				for (int[] o : options) {
					BlockPos front = him2.offset(o[0] * 3, 1, o[1] * 3);
					BlockPos back = him2.offset(-o[0] * 3, 1, -o[1] * 3);
					if (level.getBlockState(front).isAir() && level.getBlockState(front.above()).isAir()
							&& level.getBlockState(back).isAir() && level.getBlockState(back.above()).isAir()) return o;
				}
				return options[0];
			});
			// Yaw convention: 0 faces +z, 180 faces -z, 90 faces -x, -90 faces +x.
			float hisYaw = axis[1] == 1 ? 0.0F : axis[1] == -1 ? 180.0F : axis[0] == 1 ? -90.0F : 90.0F;
			server.runCommand("data modify entity @e[type=warfront:dracula,limit=1] Rotation set value [" + hisYaw + "f, 0f]");
			double frontX = him2.getX() + 0.5 + axis[0] * 3, frontZ = him2.getZ() + 0.5 + axis[1] * 3;
			double backX = him2.getX() + 0.5 - axis[0] * 3, backZ = him2.getZ() + 0.5 - axis[1] * 3;
			server.runCommand("tp @p " + frontX + " " + (him2.getY() + 1) + " " + frontZ + " " + (hisYaw + 180.0F) + " 5");
			context.waitTicks(80);
			context.takeScreenshot("dracula_count_face_to_face");
			server.runCommand("tp @p " + backX + " " + (him2.getY() + 1) + " " + backZ + " " + hisYaw + " 5");
			context.waitTicks(20);
			context.takeScreenshot("dracula_count_cape");
			server.runCommand("data modify entity @e[type=warfront:dracula,limit=1] NoAI set value 0b");

			// A mortal target: survival, unkillable, twenty blocks off. He must close the
			// distance (shadow step or stride) — and bats must be over the grounds by now.
			server.runCommand("gamemode survival @p");
			server.runCommand("effect give @p minecraft:resistance 1000000 255 true");
			// On real ground twenty blocks off, not a blind tp into a wall or over a drop.
			BlockPos stand = server.computeOnServer(minecraftServer -> {
				var level = minecraftServer.overworld();
				for (int dx : new int[] { 20, -20, 16, -16 }) {
					for (int dy = -12; dy <= 12; dy++) {
						BlockPos p = new BlockPos(him.getX() + dx, him.getY() + dy, him.getZ());
						if (level.getBlockState(p.below()).isSolid() && level.getBlockState(p).isAir()
								&& level.getBlockState(p.above()).isAir()) return p;
					}
				}
				return him.offset(20, 0, 0);
			});
			server.runCommand("tp @p " + (stand.getX() + 0.5) + " " + stand.getY() + " " + (stand.getZ() + 0.5));
			double closing = Double.MAX_VALUE;
			for (int i = 0; i < 30 && closing > 6.0; i++) {
				context.waitTicks(10);
				closing = server.computeOnServer(minecraftServer -> {
					var player = minecraftServer.getPlayerList().getPlayers().getFirst();
					double best = Double.MAX_VALUE;
					for (var e : minecraftServer.overworld().getAllEntities()) {
						if (e instanceof DraculaEntity d) best = Math.min(best, d.distanceTo(player));
					}
					return best;
				});
			}
			if (closing > 6.0) throw new AssertionError("the Count did not close on a mortal target; distance " + closing);
			int bats = server.computeOnServer(minecraftServer -> {
				int n = 0;
				for (var e : minecraftServer.overworld().getAllEntities()) if (e instanceof net.minecraft.world.entity.ambient.Bat) n++;
				return n;
			});
			if (bats < 6) throw new AssertionError("no bats over Dracula's grounds: " + bats);
			Warfront.LOGGER.info("DRACULA_TEST closed to {} blocks on a mortal; {} bats aloft", closing, bats);
			server.runCommand("gamemode spectator @p");

			// Leaving the grounds lifts the veil.
			server.runCommand("tp @p " + (origin.getX() - 200) + " 100 " + (origin.getZ() - 200));
			context.waitTicks(40);
			if (context.computeOnClient(client -> VampireVeilClient.isActive())) {
				throw new AssertionError("the veil did not lift outside the grounds");
			}
			Warfront.LOGGER.info("DRACULA_TEST PASS baked={} risen={} health {} -> {} stalked={} closed={} bats={}",
					baked, at.toShortString(), before, after, closest, closing, bats);
		}
	}

	private static int countDracula(net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			var level = minecraftServer.overworld();
			int n = 0;
			for (var e : level.getAllEntities()) if (e instanceof DraculaEntity) n++;
			return n;
		});
	}

	private static float health(net.minecraft.server.MinecraftServer minecraftServer) {
		for (var e : minecraftServer.overworld().getAllEntities()) {
			if (e instanceof DraculaEntity d) return d.getHealth();
		}
		return -1.0F;
	}
}
