package io.github.lilkuzcodev.warfront.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.block.WarfrontBlocks;
import io.github.lilkuzcodev.warfront.c2.CosmosReconBridge;
import io.github.lilkuzcodev.warfront.c2.DisplayBlockEntity;
import io.github.lilkuzcodev.warfront.c2.DisplayFeed;
import io.github.lilkuzcodev.warfront.c2.DisplayWallLayout;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** One rate-limited dynamic texture per wall controller, shared by all its panels. */
final class DisplayTextureCache {
	private static final int SIZE = 128;
	private static final int BLOCKS_PER_PIXEL = 2;
	private static final long UPDATE_TICKS = 40;
	private static final long UNUSED_TICKS = 200;
	private static final AtomicLong NEXT_TEXTURE_ID = new AtomicLong();
	private static final Map<CacheKey, Entry> ENTRIES = new HashMap<>();

	private record CacheKey(ResourceKey<Level> dimension, BlockPos controller) { }

	private record Entry(Identifier id, DynamicTexture texture, long tick, int signature, long lastSeen) {
		Entry refreshed(long nextTick, int nextSignature, long seen) {
			return new Entry(id, texture, nextTick, nextSignature, seen);
		}
		Entry seen(long seen) { return new Entry(id, texture, tick, signature, seen); }
	}

	static Identifier texture(DisplayBlockEntity display, DisplayWallLayout wall) {
		ClientLevel level = (ClientLevel) display.getLevel();
		if (level.getBlockEntity(wall.controller()) instanceof DisplayBlockEntity controller) {
			display = controller;
		}
		CacheKey key = new CacheKey(level.dimension(), wall.controller().immutable());
		Entry entry = ENTRIES.get(key);
		if (entry == null) {
			Identifier id = Warfront.id("dynamic/display_" + Long.toUnsignedString(NEXT_TEXTURE_ID.incrementAndGet(), 36));
			DynamicTexture texture = new DynamicTexture(() -> "Warfront C2 display " + key, SIZE, SIZE, false);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			entry = new Entry(id, texture, Long.MIN_VALUE, 0, level.getGameTime());
			ENTRIES.put(key, entry);
		}
		int signature = signature(display, wall);
		if (signature != entry.signature || refreshDue(level.getGameTime(), entry.tick)) {
			draw(entry.texture, level, display);
			entry.texture.upload();
			entry = entry.refreshed(level.getGameTime(), signature, level.getGameTime());
		} else entry = entry.seen(level.getGameTime());
		ENTRIES.put(key, entry);
		return entry.id;
	}

	private static boolean refreshDue(long gameTime, long lastTick) {
		return lastTick == Long.MIN_VALUE || gameTime < lastTick || gameTime - lastTick >= UPDATE_TICKS;
	}

	static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			if (!ENTRIES.isEmpty()) clear();
			return;
		}
		long now = level.getGameTime();
		Iterator<Map.Entry<CacheKey, Entry>> iterator = ENTRIES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<CacheKey, Entry> cached = iterator.next();
			CacheKey key = cached.getKey();
			Entry entry = cached.getValue();
			boolean wrongDimension = !key.dimension.equals(level.dimension());
			boolean expired = now < entry.lastSeen || now - entry.lastSeen > UNUSED_TICKS;
			boolean removed = !wrongDimension && level.hasChunk(key.controller.getX() >> 4, key.controller.getZ() >> 4)
					&& level.getBlockState(key.controller).getBlock() != WarfrontBlocks.SCREEN
					&& level.getBlockState(key.controller).getBlock() != WarfrontBlocks.PROJECTOR;
			if (wrongDimension || expired || removed) {
				client.getTextureManager().release(entry.id);
				iterator.remove();
			}
		}
	}

	static void clear() {
		Minecraft client = Minecraft.getInstance();
		for (Entry entry : ENTRIES.values()) {
			client.getTextureManager().release(entry.id);
		}
		ENTRIES.clear();
	}

	private static int signature(DisplayBlockEntity display, DisplayWallLayout wall) {
		int result = display.feed().hashCode();
		result = 31 * result + display.centerX();
		result = 31 * result + display.centerZ();
		result = 31 * result + wall.width();
		result = 31 * result + wall.height();
		result = 31 * result + display.dataRevision();
		return result;
	}

	private static void draw(DynamicTexture texture, ClientLevel level, DisplayBlockEntity display) {
		NativeImage image = texture.getPixels();
		if (image == null) return;
		int centerX = display.centerX();
		int centerZ = display.centerZ();
		if (display.feed() == DisplayFeed.SATELLITE && display.satelliteInPass()) {
			centerX = (int) Math.round(display.satelliteX());
			centerZ = (int) Math.round(display.satelliteZ());
		}
		image.fillRect(0, 0, SIZE, SIZE, 0xFF0A1110);
		if (display.feed() == DisplayFeed.SATELLITE) drawSatelliteTerrain(image, display);
		else drawLocalTerrain(image, level, centerX, centerZ);
		if (display.feed() == DisplayFeed.TACTICAL) {
			drawTactical(image, display.tacticalMarkers(), centerX, centerZ);
		} else if (display.feed() == DisplayFeed.SATELLITE) {
			drawSatellite(image, display, centerX, centerZ);
		}
		drawBezel(image);
	}

	private static void drawLocalTerrain(NativeImage image, ClientLevel level, int centerX, int centerZ) {
		for (int py = 0; py < SIZE; py += 2) {
			for (int px = 0; px < SIZE; px += 2) {
				int worldX = centerX + (px - SIZE / 2) * BLOCKS_PER_PIXEL;
				int worldZ = centerZ + (py - SIZE / 2) * BLOCKS_PER_PIXEL;
				image.fillRect(px, py, 2, 2, terrain(level, worldX, worldZ));
			}
		}
	}

	private static void drawSatelliteTerrain(NativeImage image, DisplayBlockEntity display) {
		int[] pixels = display.satellitePixels();
		if (!display.satelliteInPass() || !display.satelliteComms()
				|| pixels.length != CosmosReconBridge.SNAPSHOT_SIZE * CosmosReconBridge.SNAPSHOT_SIZE) return;
		int scale = SIZE / CosmosReconBridge.SNAPSHOT_SIZE;
		for (int y = 0; y < CosmosReconBridge.SNAPSHOT_SIZE; y++) {
			for (int x = 0; x < CosmosReconBridge.SNAPSHOT_SIZE; x++) {
				image.fillRect(x * scale, y * scale, scale, scale,
						pixels[y * CosmosReconBridge.SNAPSHOT_SIZE + x]);
			}
		}
	}

	private static int terrain(ClientLevel level, int x, int z) {
		if (!level.hasChunk(x >> 4, z >> 4)) return 0xFF07100D;
		int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		BlockPos pos = new BlockPos(x, Math.max(level.getMinY(), y), z);
		int argb = level.getBlockState(pos).getMapColor(level, pos).calculateARGBColor(MapColor.Brightness.NORMAL);
		return toAbgr(argb);
	}

	private static void drawTactical(NativeImage image, int[] markers, int centerX, int centerZ) {
		for (int i = 0; i + 3 < markers.length; i += 4) {
			int kind = markers[i];
			int x = toPixel(markers[i + 1], centerX);
			int y = toPixel(markers[i + 2], centerZ);
			int color = rgbToAbgr(markers[i + 3]);
			if (kind == 0) {
				circle(image, x, y, 9, (color & 0x00FFFFFF) | 0x54000000);
				square(image, x, y, 2, color);
			} else if (kind == 1) {
				cross(image, x, y, 3, color);
			} else {
				diamond(image, x, y, 4, color);
			}
		}
	}

	private static void drawSatellite(NativeImage image, DisplayBlockEntity display, int centerX, int centerZ) {
		for (int y = 4; y < SIZE - 4; y += 8) {
			for (int x = 4; x < SIZE - 4; x++) blend(image, x, y, 0x2800FF88);
		}
		int radius = Math.clamp((int) Math.round(display.satelliteRadius() / BLOCKS_PER_PIXEL), 3, 62);
		circleOutline(image, SIZE / 2, SIZE / 2, radius,
				display.satelliteComms() ? 0xFF44FF99 : 0xFF4488FF);
		int[] signals = display.satelliteSignals();
		for (int i = 0; i + 1 < signals.length; i += 2) {
			diamond(image, toPixel(signals[i], centerX), toPixel(signals[i + 1], centerZ), 3, 0xFF55FFFF);
		}
		if (!display.satelliteInPass()) image.fillRect(3, 3, SIZE - 6, 3, 0xFF3040C0);
	}

	private static int toPixel(int world, int center) {
		return (world - center) / BLOCKS_PER_PIXEL + SIZE / 2;
	}

	private static void drawBezel(NativeImage image) {
		for (int i = 0; i < SIZE; i++) {
			image.setPixelABGR(i, 0, 0xFF25352F);
			image.setPixelABGR(i, SIZE - 1, 0xFF25352F);
			image.setPixelABGR(0, i, 0xFF25352F);
			image.setPixelABGR(SIZE - 1, i, 0xFF25352F);
		}
		cross(image, SIZE / 2, SIZE / 2, 2, 0xFFB0FFD0);
	}

	private static void square(NativeImage image, int cx, int cy, int radius, int color) {
		for (int y = cy - radius; y <= cy + radius; y++)
			for (int x = cx - radius; x <= cx + radius; x++) set(image, x, y, color);
	}

	private static void cross(NativeImage image, int cx, int cy, int radius, int color) {
		for (int d = -radius; d <= radius; d++) { set(image, cx + d, cy, color); set(image, cx, cy + d, color); }
	}

	private static void diamond(NativeImage image, int cx, int cy, int radius, int color) {
		for (int y = -radius; y <= radius; y++) {
			int half = radius - Math.abs(y);
			for (int x = -half; x <= half; x++) set(image, cx + x, cy + y, color);
		}
	}

	private static void circle(NativeImage image, int cx, int cy, int radius, int color) {
		for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++)
			if (x * x + y * y <= radius * radius) blend(image, cx + x, cy + y, color);
	}

	private static void circleOutline(NativeImage image, int cx, int cy, int radius, int color) {
		int inner = (radius - 1) * (radius - 1), outer = (radius + 1) * (radius + 1);
		for (int y = -radius - 1; y <= radius + 1; y++) for (int x = -radius - 1; x <= radius + 1; x++) {
			int distance = x * x + y * y;
			if (distance >= inner && distance <= outer) set(image, cx + x, cy + y, color);
		}
	}

	private static void set(NativeImage image, int x, int y, int color) {
		if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) image.setPixelABGR(x, y, color);
	}

	private static void blend(NativeImage image, int x, int y, int source) {
		if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
		int destination = image.getPixel(x, y);
		int alpha = source >>> 24;
		int inverse = 255 - alpha;
		int r = ((source & 0xFF) * alpha + (destination & 0xFF) * inverse) / 255;
		int g = ((source >>> 8 & 0xFF) * alpha + (destination >>> 8 & 0xFF) * inverse) / 255;
		int b = ((source >>> 16 & 0xFF) * alpha + (destination >>> 16 & 0xFF) * inverse) / 255;
		image.setPixelABGR(x, y, 0xFF000000 | b << 16 | g << 8 | r);
	}

	private static int toAbgr(int argb) {
		return argb & 0xFF00FF00 | argb >>> 16 & 0xFF | (argb & 0xFF) << 16;
	}

	private static int rgbToAbgr(int rgb) {
		return 0xFF000000 | rgb >> 16 & 0xFF | rgb & 0xFF00 | (rgb & 0xFF) << 16;
	}

	private DisplayTextureCache() {
	}
}
