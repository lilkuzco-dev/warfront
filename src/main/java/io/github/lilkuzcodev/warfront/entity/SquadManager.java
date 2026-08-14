package io.github.lilkuzcodev.warfront.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * In-memory squad tracking: spawn-time membership, loss fractions for doctrine retreat
 * decisions, and Sarab-style scatter broadcasts. Squads are ephemeral by design —
 * soldiers persist their squad id, and squads lazily re-register on load.
 */
public final class SquadManager {
	public record Squad(String faction, int initialSize, List<UUID> members, BlockPos rally) {
	}

	private static final Map<UUID, Squad> SQUADS = new HashMap<>();
	private static final Map<UUID, Integer> DEATHS = new HashMap<>();

	public static UUID createSquad(String faction, int size, BlockPos rally) {
		UUID id = UUID.randomUUID();
		SQUADS.put(id, new Squad(faction, size, new ArrayList<>(), rally));
		return id;
	}

	public static void join(UUID squadId, SoldierEntity soldier) {
		Squad squad = SQUADS.get(squadId);
		if (squad != null) {
			squad.members().add(soldier.getUUID());
		}
		soldier.setSquadId(squadId);
	}

	/** Lazily re-registers squads for soldiers loaded from disk. */
	public static void ensureRegistered(SoldierEntity soldier) {
		UUID id = soldier.getSquadId();
		if (id != null && !SQUADS.containsKey(id)) {
			Squad squad = new Squad(soldier.getFaction(), estimateSquadSize(soldier), new ArrayList<>(),
					soldier.getHomePos() == null ? soldier.blockPosition() : soldier.getHomePos());
			squad.members().add(soldier.getUUID());
			SQUADS.put(id, squad);
		}
	}

	private static int estimateSquadSize(SoldierEntity soldier) {
		return Math.max(soldier.doctrine().preferredSquadSize(), 2);
	}

	public static void onSoldierDeath(SoldierEntity soldier) {
		UUID id = soldier.getSquadId();
		if (id != null) {
			DEATHS.merge(id, 1, Integer::sum);
		}
	}

	/** Fraction of the squad lost so far (0..1). */
	public static float lossFraction(UUID squadId) {
		Squad squad = SQUADS.get(squadId);
		if (squad == null || squad.initialSize() <= 0) {
			return 0.0F;
		}
		return Math.min(1.0F, DEATHS.getOrDefault(squadId, 0) / (float) squad.initialSize());
	}

	/** Broadcasts a scatter to every loaded member of the squad (Sarab doctrine). */
	public static void scatterSquad(UUID squadId, net.minecraft.world.level.Level level, int ticks) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		AABB everywhere = new AABB(-3.0E7, -512, -3.0E7, 3.0E7, 512, 3.0E7);
		for (Entity entity : serverLevel.getEntitiesOfClass(SoldierEntity.class, everywhere,
				s -> squadId.equals(s.getSquadId()))) {
			((SoldierEntity) entity).scatter(ticks);
		}
	}

	private SquadManager() {
	}
}
