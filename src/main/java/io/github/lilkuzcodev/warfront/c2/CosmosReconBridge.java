package io.github.lilkuzcodev.warfront.c2;

import io.github.lilkuzcodev.warfront.Warfront;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Reflection-only adapter to Cosmos's public satellite APIs. Keeping every Cosmos type
 * out of our signatures is what makes this a true soft dependency rather than a jar
 * that merely claims one in metadata.
 */
public final class CosmosReconBridge {
	public static final int SNAPSHOT_SIZE = 64;
	private static final int SNAPSHOT_BLOCKS_PER_PIXEL = DisplayBlockEntity.MAP_RADIUS * 2 / SNAPSHOT_SIZE;

	public record Snapshot(String satelliteId, String satelliteName, double groundX, double groundZ,
			double footprintRadius, boolean inPass, boolean commsLink, int artificialBlocks,
			List<BlockPos> strongestSignals, int[] terrainPixels) {
		public Snapshot {
			strongestSignals = List.copyOf(strongestSignals);
			terrainPixels = terrainPixels.clone();
		}
	}

	private static boolean warned;

	public static boolean available() {
		return FabricLoader.getInstance().isModLoaded("cosmos");
	}

	public static Optional<Snapshot> image(ServerLevel level, UUID owner, String preferredSatellite,
			BlockPos displayCenter) {
		if (!available() || !level.dimension().equals(Level.OVERWORLD)) {
			return Optional.empty();
		}
		try {
			Class<?> constellationClass = Class.forName("dev.lilkuzco.cosmos.satellite.SatelliteConstellation");
			Object constellation = constellationClass.getMethod("of", ServerLevel.class)
					.invoke(null, level.getServer().overworld());
			@SuppressWarnings("unchecked")
			List<Object> entries = (List<Object>) constellationClass.getMethod("ownedBy", UUID.class)
					.invoke(constellation, owner);
			Object chosenRecord = null;
			for (Object entry : entries) {
				Object record = entry.getClass().getMethod("record").invoke(entry);
				Object payload = record.getClass().getMethod("payload").invoke(record);
				String id = (String) record.getClass().getMethod("id").invoke(record);
				if (!"RECON".equals(payload.toString())) {
					continue;
				}
				if (chosenRecord == null || id.equals(preferredSatellite)) {
					chosenRecord = record;
				}
				if (id.equals(preferredSatellite)) {
					break;
				}
			}
			if (chosenRecord == null) {
				return Optional.empty();
			}

			String id = (String) chosenRecord.getClass().getMethod("id").invoke(chosenRecord);
			String name = (String) chosenRecord.getClass().getMethod("name").invoke(chosenRecord);
			Object payload = chosenRecord.getClass().getMethod("payload").invoke(chosenRecord);

			Class<?> serviceClass = Class.forName("dev.lilkuzco.kinetics.fabric.KineticsMod");
			Object service = serviceClass.getMethod("service").invoke(null);
			if (service == null) {
				return Optional.empty();
			}
			Object orbits = service.getClass().getMethod("orbits").invoke(service);
			double time = (double) service.getClass().getMethod("worldTimeSeconds").invoke(service);
			Object state = orbits.getClass().getMethod("stateAt", String.class, double.class).invoke(orbits, id, time);
			if (state == null) {
				return Optional.empty();
			}
			Object groundTrack = state.getClass().getMethod("groundTrack").invoke(state);
			double groundX = (double) groundTrack.getClass().getMethod("worldX").invoke(groundTrack);
			double groundZ = (double) groundTrack.getClass().getMethod("worldZ").invoke(groundTrack);
			double sensorAngle = (double) payload.getClass().getMethod("sensorHalfAngleDeg").invoke(payload);
			double radius = (double) groundTrack.getClass().getMethod("footprintRadius", double.class)
					.invoke(groundTrack, sensorAngle);
			double dx = groundX - displayCenter.getX();
			double dz = groundZ - displayCenter.getZ();
			boolean inPass = dx * dx + dz * dz <= radius * radius;

			Class<?> commsClass = Class.forName("dev.lilkuzco.cosmos.satellite.CommsCoverage");
			boolean comms = (boolean) commsClass.getMethod("hasCoverage", ServerLevel.class, BlockPos.class)
					.invoke(null, level, displayCenter);

			int artificial = 0;
			List<BlockPos> signals = List.of();
			int[] terrainPixels = new int[0];
			if (inPass && comms) {
				Class<?> imagerClass = Class.forName("dev.lilkuzco.cosmos.satellite.ReconImager");
				Method image = findImageMethod(imagerClass);
				Object report = image.invoke(null, level, id, groundTrack, sensorAngle);
				artificial = (int) report.getClass().getMethod("artificialBlocks").invoke(report);
				@SuppressWarnings("unchecked")
				List<BlockPos> reportSignals = (List<BlockPos>) report.getClass().getMethod("strongestSignals").invoke(report);
				signals = List.copyOf(new ArrayList<>(reportSignals));
				terrainPixels = captureTerrain(level, (int) Math.round(groundX), (int) Math.round(groundZ));
			}
			return Optional.of(new Snapshot(id, name, groundX, groundZ, radius, inPass, comms, artificial, signals,
					terrainPixels));
		} catch (ReflectiveOperationException | LinkageError error) {
			warnOnce(error);
			return Optional.empty();
		}
	}

	/** Cycles a player's owned recon payloads in a deterministic order. */
	public static Optional<String> nextReconSatelliteId(ServerLevel level, UUID owner, String current) {
		if (!available() || !level.dimension().equals(Level.OVERWORLD)) return Optional.empty();
		try {
			Class<?> constellationClass = Class.forName("dev.lilkuzco.cosmos.satellite.SatelliteConstellation");
			Object constellation = constellationClass.getMethod("of", ServerLevel.class)
					.invoke(null, level.getServer().overworld());
			@SuppressWarnings("unchecked")
			List<Object> entries = (List<Object>) constellationClass.getMethod("ownedBy", UUID.class)
					.invoke(constellation, owner);
			List<String> ids = new ArrayList<>();
			for (Object entry : entries) {
				Object record = entry.getClass().getMethod("record").invoke(entry);
				Object payload = record.getClass().getMethod("payload").invoke(record);
				if ("RECON".equals(payload.toString())) ids.add((String) record.getClass().getMethod("id").invoke(record));
			}
			ids.sort(String::compareTo);
			if (ids.isEmpty()) return Optional.empty();
			int index = ids.indexOf(current);
			return Optional.of(ids.get((index + 1) % ids.size()));
		} catch (ReflectiveOperationException | LinkageError error) {
			warnOnce(error);
			return Optional.empty();
		}
	}

	private static int[] captureTerrain(ServerLevel level, int centerX, int centerZ) {
		int[] pixels = new int[SNAPSHOT_SIZE * SNAPSHOT_SIZE];
		for (int py = 0; py < SNAPSHOT_SIZE; py++) {
			for (int px = 0; px < SNAPSHOT_SIZE; px++) {
				int x = centerX + (px - SNAPSHOT_SIZE / 2) * SNAPSHOT_BLOCKS_PER_PIXEL;
				int z = centerZ + (py - SNAPSHOT_SIZE / 2) * SNAPSHOT_BLOCKS_PER_PIXEL;
				int color = 0xFF07100D;
				if (level.hasChunk(x >> 4, z >> 4)) {
					int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
					BlockPos pos = new BlockPos(x, Math.max(level.getMinY(), y), z);
					int argb = level.getBlockState(pos).getMapColor(level, pos)
							.calculateARGBColor(MapColor.Brightness.NORMAL);
					color = satelliteTone(toAbgr(argb));
				}
				pixels[py * SNAPSHOT_SIZE + px] = color;
			}
		}
		return pixels;
	}

	private static int satelliteTone(int abgr) {
		int r = abgr & 0xFF;
		int g = abgr >>> 8 & 0xFF;
		int b = abgr >>> 16 & 0xFF;
		int luminance = (r * 3 + g * 6 + b) / 10;
		return 0xFF000000 | Math.min(255, luminance + 28) << 8 | Math.min(255, luminance / 3) << 16
				| Math.min(255, luminance / 2);
	}

	private static int toAbgr(int argb) {
		return argb & 0xFF00FF00 | argb >>> 16 & 0xFF | (argb & 0xFF) << 16;
	}

	private static void warnOnce(Throwable error) {
		if (!warned) {
			warned = true;
			Warfront.LOGGER.warn("Cosmos is loaded but its recon API could not be queried; satellite feeds are disabled", error);
		}
	}

	private static Method findImageMethod(Class<?> imagerClass) throws NoSuchMethodException {
		for (Method method : imagerClass.getMethods()) {
			if (method.getName().equals("image") && method.getParameterCount() == 4) {
				return method;
			}
		}
		throw new NoSuchMethodException("ReconImager.image(ServerLevel,String,GroundTrack,double)");
	}

	private CosmosReconBridge() {
	}
}
