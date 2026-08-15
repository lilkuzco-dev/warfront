package io.github.lilkuzcodev.warfront.systems;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.block.WarfrontBlocks;
import io.github.lilkuzcodev.warfront.data.Faction;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import io.github.lilkuzcodev.warfront.entity.WarfrontEntities;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

/**
 * The garrison ledger (v0.2.0 population overhaul). Bases self-register the first time
 * one of their template-embedded seed soldiers ticks; from then on the persistent
 * {@link WarfrontState.Base} record is the source of truth for how many soldiers the
 * base has, and this manager keeps the loaded world consistent with it:
 *
 * <ul>
 *   <li><b>Lazy hydration</b> — a freshly discovered base tops its entity population up
 *       to a stable per-base garrison target (rolled from the tier range in the faction's
 *       population JSON) only when a player is near; distant garrisons stay numbers.</li>
 *   <li><b>Reinforcement</b> — one soldier per {@code warfront:bunk} block per cycle
 *       (JSON minutes), capped at the tier target; stops entirely with no bunks left and
 *       pauses while enemies are inside the pause radius. Destroying infrastructure
 *       matters.</li>
 *   <li><b>Performance budget</b> — a global live-soldier cap per online player; bases
 *       nearest to players get spawn priority once the budget tightens.</li>
 * </ul>
 */
public final class BaseManager {
	public static final TagKey<Structure> ALL_BASES = TagKey.create(Registries.STRUCTURE, Warfront.id("bases"));

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int interval = Math.max(1, WarfrontRegistry.population().baseTickSeconds()) * 20;
			if (server.getTickCount() % interval != 0) {
				return;
			}
			tickBases(server.overworld());
		});
	}

	// ---------- discovery ----------

	/**
	 * Called from a soldier's first server tick. If the soldier stands in a warfront base
	 * structure, registers the base (first contact) and adopts the soldier into its
	 * garrison count. Seed soldiers embedded in the structure NBTs are what trigger
	 * discovery — no chunk scanning.
	 */
	public static void tryAdopt(SoldierEntity soldier) {
		if (!(soldier.level() instanceof ServerLevel level) || soldier.getFaction().isEmpty() || soldier.isRoaming()) {
			return;
		}
		// NOTE: /place structure writes no chunk references, so command-placed bases are
		// never discovered — only naturally generated ones. Verified 2026-08-15.
		StructureStart start = level.structureManager().getStructureWithPieceAt(soldier.blockPosition(), ALL_BASES);
		if (!start.isValid()) {
			return;
		}
		Identifier id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
		if (id == null || !id.getNamespace().equals(Warfront.MOD_ID)) {
			return;
		}
		BoundingBox box = start.getBoundingBox();
		String key = id.getPath() + "@" + box.minX() + "," + box.minZ();
		WarfrontState state = WarfrontState.get(level.getServer());
		WarfrontState.Base base = state.base(key);
		if (base == null) {
			base = new WarfrontState.Base(soldier.getFaction(), tierOf(id.getPath()), box.getCenter(),
					List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
					0, level.getGameTime(), false);
			state.putBase(key, base);
			Warfront.LOGGER.info("Registered base {} ({} {})", key, base.faction, base.tier);
		}
		if (soldier.getBaseKey().isEmpty()) {
			soldier.setBaseKey(key);
			base.garrison++;
			state.markBasesDirty();
		}
	}

	private static String tierOf(String structurePath) {
		if (structurePath.contains("headquarters")) {
			return "headquarters";
		}
		return structurePath.contains("forward") ? "forward_base" : "outpost";
	}

	public static void onSoldierDeath(ServerLevel level, String baseKey) {
		WarfrontState state = WarfrontState.get(level.getServer());
		WarfrontState.Base base = state.base(baseKey);
		if (base != null && base.garrison > 0) {
			base.garrison--;
			state.markBasesDirty();
		}
	}

	// ---------- the base cycle ----------

	private static void tickBases(ServerLevel level) {
		WarfrontState state = WarfrontState.get(level.getServer());
		if (state.bases().isEmpty() || level.players().isEmpty()) {
			return;
		}
		int hydrationRadius = WarfrontRegistry.population().hydrationRadius();
		// nearest-base priority: process bases closest to a player first so the global
		// budget is spent where someone can see it
		List<String> keys = new ArrayList<>(state.bases().keySet());
		keys.sort(java.util.Comparator.comparingDouble(k -> distToNearestPlayer(level, state.base(k).center)));
		int budget = spawnBudget(level);
		for (String key : keys) {
			WarfrontState.Base base = state.base(key);
			if (budget <= 0 || distToNearestPlayer(level, base.center) > hydrationRadius
					|| !level.isLoaded(base.center)) {
				continue;
			}
			Faction faction = WarfrontRegistry.faction(base.faction);
			if (faction == null) {
				continue;
			}
			int target = faction.population().garrisonTarget(base.tier, key.hashCode());
			if (!base.hydrated) {
				budget -= hydrate(level, key, base, target);
				base.hydrated = true;
				state.markBasesDirty();
			} else {
				budget -= reinforce(level, key, base, faction, target);
			}
		}
	}

	/** Live soldiers the global budget still allows (cap scales with online players). */
	private static int spawnBudget(ServerLevel level) {
		int cap = WarfrontRegistry.population().perPlayerSoldierCap() * level.players().size();
		int loaded = level.getEntitiesOfClass(SoldierEntity.class,
				new AABB(-3.0E7, -512, -3.0E7, 3.0E7, 512, 3.0E7)).size();
		return cap - loaded;
	}

	private static double distToNearestPlayer(ServerLevel level, BlockPos pos) {
		double best = Double.MAX_VALUE;
		for (ServerPlayer player : level.players()) {
			best = Math.min(best, Math.sqrt(player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ())));
		}
		return best;
	}

	/** First-approach top-up: fills the garrison to target at anchor points, no bunk requirement. */
	private static int hydrate(ServerLevel level, String key, WarfrontState.Base base, int target) {
		int live = liveGarrison(level, key).size();
		base.garrison = live;
		int spawned = 0;
		List<BlockPos> anchors = anchorPoints(level, base);
		while (base.garrison < target && spawned < target) {
			// one officer per ~8: rank rolled from current count
			if (spawnSoldier(level, key, base, anchors, base.garrison % 8 == 7 ? "officer" : "soldier") == null) {
				break;
			}
			spawned++;
		}
		return spawned;
	}

	/** The bunk-driven respawn cycle: one soldier per bunk per interval, up to the tier target. */
	private static int reinforce(ServerLevel level, String key, WarfrontState.Base base, Faction faction, int target) {
		if (base.garrison >= target) {
			return 0;
		}
		long intervalTicks = (long) (faction.population().reinforceMinutes() * 1200);
		if (level.getGameTime() - base.lastReinforce < intervalTicks) {
			return 0;
		}
		if (enemiesNear(level, base, faction.population().reinforcePauseRadius())) {
			return 0; // contested: no respawning under fire
		}
		List<BlockPos> bunks = scanBounds(level, base, WarfrontBlocks.BUNK);
		if (bunks.isEmpty()) {
			return 0; // infrastructure destroyed: garrison stays down
		}
		base.lastReinforce = level.getGameTime();
		int spawned = 0;
		for (int i = 0; i < bunks.size() && base.garrison < target; i++) {
			if (spawnSoldier(level, key, base, bunks, "soldier") == null) {
				break;
			}
			spawned++;
		}
		WarfrontState.get(level.getServer()).markBasesDirty();
		return spawned;
	}

	private static boolean enemiesNear(ServerLevel level, WarfrontState.Base base, int radius) {
		AABB area = new AABB(base.center).inflate(radius);
		WarfrontState state = WarfrontState.get(level.getServer());
		for (ServerPlayer player : level.players()) {
			if (area.contains(player.position()) && state.isHostileTo(player.getUUID(), base.faction)
					&& !player.isCreative() && !player.isSpectator()) {
				return true;
			}
		}
		return !level.getEntitiesOfClass(SoldierEntity.class, area,
				s -> !s.getFaction().isEmpty()
						&& "hostile".equals(WarfrontRegistry.relation(s.getFaction(), base.faction))).isEmpty();
	}

	private static SoldierEntity spawnSoldier(ServerLevel level, String key, WarfrontState.Base base,
			List<BlockPos> anchors, String rank) {
		BlockPos anchor = anchors.isEmpty() ? base.center
				: anchors.get(level.getRandom().nextInt(anchors.size()));
		BlockPos spawn = standablePosNear(level, anchor);
		if (spawn == null) {
			return null;
		}
		SoldierEntity soldier = WarfrontEntities.SOLDIER.create(level, EntitySpawnReason.EVENT);
		soldier.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		soldier.setFaction(base.faction);
		soldier.setRank(rank);
		soldier.setHomePos(anchor);
		soldier.setBaseKey(key);
		soldier.applyLoadout(WarfrontState.get(level.getServer()).techLevel(base.faction));
		soldier.setPersistenceRequired();
		level.addFreshEntity(soldier);
		base.garrison++;
		return soldier;
	}

	private static BlockPos standablePosNear(ServerLevel level, BlockPos anchor) {
		for (BlockPos candidate : BlockPos.betweenClosed(anchor.offset(-2, 0, -2), anchor.offset(2, 2, 2))) {
			if (level.getBlockState(candidate).isAir() && level.getBlockState(candidate.above()).isAir()
					&& !level.getBlockState(candidate.below()).isAir()) {
				return candidate.immutable();
			}
		}
		return null;
	}

	private static List<SoldierEntity> liveGarrison(ServerLevel level, String key) {
		return level.getEntitiesOfClass(SoldierEntity.class,
				new AABB(-3.0E7, -512, -3.0E7, 3.0E7, 512, 3.0E7), s -> key.equals(s.getBaseKey()));
	}

	/**
	 * Spawn anchors, distributed across the base's pieces: bunks (barracks), stations
	 * (towers, gates), falling back to the center courtyard. This is what puts soldiers
	 * on the towers and at the gates instead of one clump in the middle.
	 */
	private static List<BlockPos> anchorPoints(ServerLevel level, WarfrontState.Base base) {
		List<BlockPos> anchors = scanBounds(level, base, WarfrontBlocks.BUNK);
		anchors.addAll(scanBounds(level, base, WarfrontBlocks.SANDBAG_STATION));
		if (anchors.isEmpty()) {
			anchors.add(base.center);
		}
		return anchors;
	}

	private static List<BlockPos> scanBounds(ServerLevel level, WarfrontState.Base base,
			net.minecraft.world.level.block.Block block) {
		List<BlockPos> found = new ArrayList<>();
		List<Integer> b = base.bounds;
		if (b.size() != 6) {
			return found;
		}
		for (BlockPos pos : BlockPos.betweenClosed(b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5))) {
			if (level.getBlockState(pos).is(block)) {
				found.add(pos.immutable());
			}
		}
		return found;
	}

	private BaseManager() {
	}
}
