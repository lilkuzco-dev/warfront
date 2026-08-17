package io.github.lilkuzcodev.warfront.c2;

import io.github.lilkuzcodev.warfront.data.Faction;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * The radar/intel contract for displays. Phase 2's target registry and future radar
 * hardware register providers here; the display substrate does not own either system.
 */
public final class TacticalOverlayRegistry {
	public enum Kind { BASE, CONTACT, TARGET }

	public record Marker(Kind kind, BlockPos pos, int rgb) {
	}

	@FunctionalInterface
	public interface Provider {
		void collect(ServerLevel level, UUID viewer, BlockPos center, int radius, List<Marker> output);
	}

	private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
	private static final Map<CacheKey, List<Marker>> CACHE = new HashMap<>();
	private static MinecraftServer cacheServer;
	private static long cacheTick = Long.MIN_VALUE;
	private record CacheKey(net.minecraft.resources.ResourceKey<Level> dimension, UUID viewer,
			BlockPos center, int radius) { }

	static {
		register(TacticalOverlayRegistry::collectBases);
		register(TacticalOverlayRegistry::collectAlertedContacts);
	}

	public static void register(Provider provider) {
		PROVIDERS.add(provider);
		CACHE.clear();
	}

	public static List<Marker> snapshot(ServerLevel level, UUID viewer, BlockPos center, int radius) {
		long tick = level.getGameTime();
		if (cacheServer != level.getServer() || cacheTick != tick) {
			CACHE.clear();
			cacheServer = level.getServer();
			cacheTick = tick;
		}
		CacheKey key = new CacheKey(level.dimension(), viewer, center.immutable(), radius);
		List<Marker> cached = CACHE.get(key);
		if (cached != null) return cached;
		List<Marker> result = new ArrayList<>();
		for (Provider provider : PROVIDERS) {
			provider.collect(level, viewer, center, radius, result);
			if (result.size() >= 64) {
				break;
			}
		}
		List<Marker> snapshot = List.copyOf(result.subList(0, Math.min(64, result.size())));
		CACHE.put(key, snapshot);
		return snapshot;
	}

	private static void collectBases(ServerLevel level, UUID viewer, BlockPos center, int radius, List<Marker> output) {
		if (!level.dimension().equals(Level.OVERWORLD)) return;
		WarfrontState state = WarfrontState.get(level.getServer());
		Set<String> visibleFactions = friendlyFactions(state, viewer);
		long radiusSquared = (long) radius * radius;
		for (WarfrontState.Base base : state.bases().values()) {
			if (!visibleFactions.contains(base.faction)) continue;
			long dx = base.center.getX() - center.getX();
			long dz = base.center.getZ() - center.getZ();
			if (dx * dx + dz * dz > radiusSquared) {
				continue;
			}
			Faction faction = WarfrontRegistry.faction(base.faction);
			output.add(new Marker(Kind.BASE, base.center, faction == null ? 0x94A3B8 : faction.primaryColor()));
		}
	}

	private static void collectAlertedContacts(ServerLevel level, UUID viewer, BlockPos center, int radius,
			List<Marker> output) {
		Set<String> visibleFactions = friendlyFactions(WarfrontState.get(level.getServer()), viewer);
		if (visibleFactions.isEmpty()) return;
		AABB area = new AABB(center).inflate(radius, 256, radius);
		for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class, area,
				soldier -> soldier.getTarget() != null && visibleFactions.contains(soldier.getFaction()))) {
			output.add(new Marker(Kind.CONTACT, soldier.getTarget().blockPosition(), 0xEF4444));
		}
	}

	private static Set<String> friendlyFactions(WarfrontState state, UUID viewer) {
		float threshold = WarfrontRegistry.standing().friendlyAbove();
		return WarfrontRegistry.factions().keySet().stream()
				.filter(faction -> state.standing(viewer, faction) >= threshold)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private TacticalOverlayRegistry() {
	}
}
