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

			// Photograph him: stand four blocks off, facing him.
			server.runCommand("tp @p " + (at.getX() + 0.5) + " " + (at.getY() + 1) + " " + (at.getZ() - 3.5) + " 0 15");
			context.waitTicks(40);
			context.takeScreenshot("dracula_risen_in_keep");

			// Ten seconds of noon: roofed means the sun cannot reach him.
			float before = server.computeOnServer(minecraftServer -> health(minecraftServer));
			context.waitTicks(200);
			float after = server.computeOnServer(minecraftServer -> health(minecraftServer));
			if (countDracula(server) != 1) throw new AssertionError("Count not exactly one after rise: " + countDracula(server));
			if (after < before) throw new AssertionError("the risen Count is burning: " + before + " -> " + after);

			// Leaving the grounds lifts the veil.
			server.runCommand("tp @p " + (origin.getX() - 200) + " 100 " + (origin.getZ() - 200));
			context.waitTicks(40);
			if (context.computeOnClient(client -> VampireVeilClient.isActive())) {
				throw new AssertionError("the veil did not lift outside the grounds");
			}
			Warfront.LOGGER.info("DRACULA_TEST PASS baked={} risen={} health {} -> {}", baked, at.toShortString(), before, after);
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
