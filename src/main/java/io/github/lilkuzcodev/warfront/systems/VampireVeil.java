package io.github.lilkuzcodev.warfront.systems;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.dialogue.WarfrontNet;
import io.github.lilkuzcodev.warfront.entity.DraculaEntity;
import io.github.lilkuzcodev.warfront.entity.WarfrontEntities;
import io.github.lilkuzcodev.warfront.worldgen.CastleSites;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;

/**
 * Dracula's castle is a place apart. While a player stands inside its grounds, their sky
 * turns to midnight under a blood-red full moon and snow falls — a purely per-player,
 * purely visual veil (the payload flips client rendering; server time, weather and mob
 * logic are untouched, so the rest of the server keeps its honest day). And the Count
 * himself holds the castle: if no living Dracula is inside when a mortal enters, one
 * rises — unless a player has already slain him, in which case the castle stays his tomb.
 *
 * <p>Why the rise-on-entry shape: Dracula is baked into the castle template, but his own
 * vampire rules kill him when nobody is looking — the castle builds in daylight, he
 * wanders a courtyard, and the sun takes him long before anyone arrives. Reported from
 * play as "no Dracula unit manning the castle". Sun-death is flavour, not defeat, so he
 * simply returns at the next visit; only a player's hand ends him for good.
 *
 * <p>Rule 7: everything here runs on END_SERVER_TICK.
 */
public final class VampireVeil {

	private static final int SCAN_INTERVAL_TICKS = 10;
	/** Castle footprint width fallback; the real value is read from the template. */
	private static final int FALLBACK_WIDTH = 501;
	/** How far below/above the paste origin still counts as "inside the castle". */
	private static final int BOX_BELOW = 16;
	private static final int BOX_ABOVE = 96;

	private static final Set<UUID> VEILED = new HashSet<>();
	/** Players with the veil forced on by /warfront veil — testing and theatrics. */
	private static final Set<UUID> FORCED = new HashSet<>();

	/** Last logged reason the Count could not rise, per site — logged on change only. */
	private static final Map<String, String> WAIT_REASON = new HashMap<>();

	private record Site(String key, AABB box, BlockPos centre) {}

	private VampireVeil() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) return;
			ServerLevel level = server.overworld();
			List<Site> sites = draculaSites(level);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Site inside = null;
				if (player.level() == level) {
					for (Site site : sites) {
						if (site.box().contains(player.getX(), player.getY(), player.getZ())) {
							inside = site;
							break;
						}
					}
				}
				boolean veiled = inside != null || FORCED.contains(player.getUUID());
				boolean was = VEILED.contains(player.getUUID());
				if (veiled && !was) {
					VEILED.add(player.getUUID());
					ServerPlayNetworking.send(player, new WarfrontNet.VeilS2C(true));
				} else if (!veiled && was) {
					VEILED.remove(player.getUUID());
					ServerPlayNetworking.send(player, new WarfrontNet.VeilS2C(false));
				}
				if (inside != null) {
					ensureDracula(level, inside);
				}
			}
		});
	}

	public static void setForced(ServerPlayer player, boolean active) {
		if (active) {
			FORCED.add(player.getUUID());
		} else {
			FORCED.remove(player.getUUID());
		}
	}

	/** Marks the containing site's Dracula as slain by a player. The sun never calls this. */
	public static void onDraculaSlainByPlayer(ServerLevel level, BlockPos where) {
		for (Site site : draculaSites(level)) {
			if (site.box().contains(where.getX(), where.getY(), where.getZ())) {
				CastleSites.get(level.getServer()).markDraculaSlain(site.key());
				Warfront.LOGGER.info("DRACULA_SLAIN at site {} — he will not rise again", site.key());
				return;
			}
		}
	}

	/**
	 * The built Dracula castle sites. Sites recorded before templates were stored are
	 * backfilled from the site chunk's structure start when that chunk happens to be
	 * loaded — never by loading a chunk specially for it.
	 */
	private static List<Site> draculaSites(ServerLevel level) {
		CastleSites sites = CastleSites.get(level.getServer());
		int width = level.getServer().getStructureManager().get(Warfront.id("dracula/castle"))
				.map(t -> Math.max(t.getSize().getX(), t.getSize().getZ())).orElse(FALLBACK_WIDTH);
		List<Site> out = new ArrayList<>();
		for (var entry : sites.all().entrySet()) {
			String key = entry.getKey();
			String template = sites.template(key);
			if (template == null) {
				template = backfillTemplate(level, sites, key);
			}
			if (!"warfront:dracula/castle".equals(template)) continue;
			BlockPos origin = sites.origin(key);
			if (origin == null) continue;
			AABB box = new AABB(origin.getX(), origin.getY() - BOX_BELOW, origin.getZ(),
					origin.getX() + width, origin.getY() + BOX_ABOVE, origin.getZ() + width);
			out.add(new Site(key, box, new BlockPos(origin.getX() + width / 2,
					origin.getY(), origin.getZ() + width / 2)));
		}
		return out;
	}

	private static @org.jspecify.annotations.Nullable String backfillTemplate(ServerLevel level,
			CastleSites sites, String key) {
		int comma = key.indexOf(',');
		if (key.startsWith("test/") || comma < 0) return null;
		int chunkX = Integer.parseInt(key.substring(0, comma));
		int chunkZ = Integer.parseInt(key.substring(comma + 1));
		if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) return null;
		for (var startEntry : level.getChunk(chunkX, chunkZ).getAllStarts().entrySet()) {
			var id = level.registryAccess()
					.lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
					.getKey(startEntry.getKey());
			if (id != null && id.getNamespace().equals(Warfront.MOD_ID) && id.getPath().endsWith("_castle")) {
				String faction = id.getPath().substring(0, id.getPath().length() - "_castle".length());
				var template = Warfront.id(faction + "/castle");
				sites.recordTemplate(key, template);
				return template.toString();
			}
		}
		return null;
	}

	/** The Count rises if his castle stands empty and no player has ever struck him down. */
	private static void ensureDracula(ServerLevel level, Site site) {
		CastleSites sites = CastleSites.get(level.getServer());
		if (sites.isDraculaSlain(site.key())) return;
		// The search box is wider than the castle: a chase can lure the Count past his
		// grounds, and a spawn check that cannot see him out there would raise a second
		// Dracula while the first walks home. (Audit finding, 2026-08-21.)
		int present = level.getEntitiesOfClass(DraculaEntity.class, site.box().inflate(96.0)).size();
		if (present > 0) {
			waiting(site, "the Count already walks his grounds (" + present + ")");
			return;
		}
		if (!level.isLoaded(site.centre())) {
			waiting(site, "castle centre " + site.centre().toShortString() + " not loaded");
			return; // the next scan retries as the visitor walks inward
		}
		BlockPos spot = roofedSpotNear(level, site.centre());
		if (spot == null) {
			waiting(site, "no roofed spot within 40 of " + site.centre().toShortString());
			return;
		}
		DraculaEntity dracula = WarfrontEntities.DRACULA.create(level, EntitySpawnReason.EVENT);
		if (dracula == null) return;
		dracula.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.0F, 0.0F);
		dracula.setHomeTo(spot, 40);
		level.addFreshEntity(dracula);
		WAIT_REASON.remove(site.key());
		Warfront.LOGGER.info("DRACULA_RISES at {} (site {})", spot.toShortString(), site.key());
	}

	/**
	 * Every way the rise can be withheld says so in the log, once per change of reason.
	 * It was silent before, and a 0.4.16 world had a visitor under the veil for four
	 * minutes with no Count and no line to explain it — the roofed-spot search had been
	 * returning null on every scan (see {@link #roofedSpotNear}).
	 */
	private static void waiting(Site site, String reason) {
		String last = WAIT_REASON.put(site.key(), reason);
		if (!reason.equals(last)) {
			Warfront.LOGGER.info("DRACULA_WAITS site {}: {}", site.key(), reason);
		}
	}

	/**
	 * The highest standable spot with a roof near the castle centre — indoors, where the
	 * sun cannot take him a second time. Null when the centre chunks are not loaded.
	 *
	 * <p>This is the importer's {@code findThroneChamber} walk (tools/import-grand-castle.js):
	 * each column is descended from the top and the first roofed floor wins. The port
	 * originally abandoned a column at its first <em>unroofed</em> floor — but the first
	 * floor under the sky in nearly every castle column is the roof or the courtyard, so
	 * the walk never reached a single room and the Count never rose. The importer keeps
	 * descending; so does this now.
	 */
	private static @org.jspecify.annotations.Nullable BlockPos roofedSpotNear(ServerLevel level, BlockPos centre) {
		if (!level.isLoaded(centre)) return null;
		BlockPos best = null;
		for (int dx = -40; dx <= 40; dx += 4) {
			for (int dz = -40; dz <= 40; dz += 4) {
				int x = centre.getX() + dx;
				int z = centre.getZ() + dz;
				if (!level.isLoaded(new BlockPos(x, centre.getY(), z))) continue;
				for (int y = centre.getY() + BOX_ABOVE - 16; y > centre.getY() - BOX_BELOW; y--) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!level.getBlockState(pos.below()).isSolid()) continue;
					if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) continue;
					boolean roofed = false;
					for (int ry = 2; ry <= 14 && !roofed; ry++) {
						roofed = !level.getBlockState(pos.above(ry)).isAir();
					}
					if (!roofed) continue; // a roof or courtyard surface; the rooms are below it
					if (best == null || y > best.getY()) best = pos;
					break;
				}
			}
		}
		return best;
	}
}
