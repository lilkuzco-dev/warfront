package io.github.lilkuzcodev.warfront.systems;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.block.WarfrontBlocks;
import io.github.lilkuzcodev.warfront.civilization.CivilizationManager;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState;
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
	private static final int DISCOVERY_SETTLE_TICKS = 200;
	/** How far from the settlement heart civilians live and are counted. */
	private static final int SETTLEMENT_RADIUS = 40;
	/** Spawn-anchor search radius around a base centre; see {@link #anchorPoints}. */
	private static final int ANCHOR_SCAN_RADIUS = 64;
	private static final int HOUSING_RECOUNT_TICKS = 6_000;
	private static final java.util.Map<String, Long> LAST_HOUSING_COUNT = new java.util.HashMap<>();

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerLevel level = server.overworld();
			if (server.getTickCount() % 20 == 0) {
				discoverBasesAtPlayers(level);
			}
			int interval = Math.max(1, WarfrontRegistry.population().baseTickSeconds()) * 20;
			if (server.getTickCount() % interval != 0) {
				return;
			}
			tickBases(level);
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
		String key = registerBaseAt(level, soldier.blockPosition(), soldier.getFaction());
		if (key == null) {
			return;
		}
		WarfrontState state = WarfrontState.get(level.getServer());
		WarfrontState.Base base = state.base(key);
		// A soldier passing through a rival structure (for example during an assault)
		// must never become part of that enemy base's persistent garrison ledger.
		if (base == null || !base.faction.equals(soldier.getFaction())) {
			return;
		}
		if (soldier.getBaseKey().isEmpty()) {
			soldier.setBaseKey(key);
			base.garrison++;
			state.markBasesDirty();
		}
	}

	/**
	 * Fallback discovery for a base whose template entities were removed or failed to
	 * load. Reaching any actual structure piece registers it; normal seed soldiers are
	 * still the earlier and wider discovery path.
	 */
	private static void discoverBasesAtPlayers(ServerLevel level) {
		for (ServerPlayer player : level.players()) {
			registerBaseAt(level, player.blockPosition(), null);
		}
	}

	private static @org.jspecify.annotations.Nullable String registerBaseAt(ServerLevel level, BlockPos pos,
			@org.jspecify.annotations.Nullable String fallbackFaction) {
		// NOTE: /place structure writes no chunk references, so command-placed bases are
		// not discoverable through StructureManager. Naturally generated bases are.
		StructureStart start = level.structureManager().getStructureWithPieceAt(pos, ALL_BASES);
		if (!start.isValid()) {
			return null;
		}
		Identifier id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
		if (id == null || !id.getNamespace().equals(Warfront.MOD_ID)) {
			return null;
		}
		BoundingBox box = start.getBoundingBox();
		String key = id.getPath() + "@" + box.minX() + "," + box.minZ();
		WarfrontState state = WarfrontState.get(level.getServer());
		if (state.base(key) == null) {
			String faction = factionOf(id.getPath());
			if (faction == null) {
				faction = fallbackFaction;
			}
			if (faction == null || WarfrontRegistry.faction(faction) == null) {
				return null;
			}
			WarfrontState.Base base = new WarfrontState.Base(faction, tierOf(id.getPath()), box.getCenter(),
					List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
					0, level.getGameTime(), false);
			state.putBase(key, base);
			Warfront.LOGGER.info("Registered base {} ({} {})", key, base.faction, base.tier);
			// A base is not only a garrison: the people who keep it running live here too.
			// Seeded once, at first contact, around the START PIECE rather than the whole
			// structure box — jigsaw sprawl can stretch that box hundreds of blocks, and
			// its centre need not be anywhere near the built plate.
			seedCivilians(level, key, base, startPieceCenter(start, box));
		}
		return key;
	}

	/**
	 * Recounts the settlement's roofs so population growth is bounded by what is actually
	 * standing. Throttled hard: this is a block scan, and it only matters on the timescale
	 * of someone building or burning down a barracks.
	 */
	private static void refreshHousing(ServerLevel level, String key, WarfrontState.Base base) {
		long now = level.getGameTime();
		Long last = LAST_HOUSING_COUNT.get(key);
		if (last != null && now - last < HOUSING_RECOUNT_TICKS) {
			return;
		}
		LAST_HOUSING_COUNT.put(key, now);
		var state = CivilizationState.get(level.getServer());
		var city = state.city(CivilizationManager.normalizeId("base_" + key));
		if (city == null) {
			return;
		}
		int bunks = scanNearCentre(level, base, WarfrontBlocks.BUNK, ANCHOR_SCAN_RADIUS).size();
		// A bunk is a household, not a bed. The rethemed village houses carry only a
		// handful of beds each, so counting them one-for-one put every settlement's
		// ceiling below its own seeded population and growth could never start.
		int housing = Math.max(city.citizens().size(),
				bunks * WarfrontRegistry.population().citizensPerBunk());
		if (housing != city.housing()) {
			Warfront.LOGGER.debug("{} housing {} -> {} ({} bunks)", city.id(), city.housing(), housing, bunks);
			state.putCity(city.withHousing(housing));
		}
	}

	/** Centre of the plate the structure started from, which is where the settlement is. */
	private static BlockPos startPieceCenter(StructureStart start, BoundingBox fallback) {
		var pieces = start.getPieces();
		return pieces.isEmpty() ? fallback.getCenter() : pieces.get(0).getBoundingBox().getCenter();
	}

	/**
	 * Attaches this structure's civilian population. Homes are real standable spots
	 * inside the footprint, so citizens are never seeded inside a wall.
	 */
	private static void seedCivilians(ServerLevel level, String key, WarfrontState.Base base, BlockPos heart) {
		int population = WarfrontRegistry.population().citizensForTier(base.tier);
		if (population < 1) {
			return;
		}
		CivilizationManager.seedSettlement(level, "base_" + key, base.faction, heart,
				SETTLEMENT_RADIUS, population, standableSpots(level, heart, population * 2));
	}

	/**
	 * Standable positions around the settlement heart, nearest first.
	 *
	 * <p>Scans the whole disc before choosing, rather than taking the first hits it
	 * finds: filling greedily drains entirely out of the first column scanned and
	 * strings every citizen along one edge instead of spreading them through the town.
	 */
	private static List<BlockPos> standableSpots(ServerLevel level, BlockPos heart, int wanted) {
		List<BlockPos> found = new ArrayList<>();
		int top = heart.getY() + 16;
		int bottom = heart.getY() - 12;
		for (int dx = -SETTLEMENT_RADIUS; dx <= SETTLEMENT_RADIUS; dx += 3) {
			for (int dz = -SETTLEMENT_RADIUS; dz <= SETTLEMENT_RADIUS; dz += 3) {
				int x = heart.getX() + dx;
				int z = heart.getZ() + dz;
				if (!level.isLoaded(new BlockPos(x, heart.getY(), z))) {
					continue;
				}
				for (int y = top; y >= bottom; y--) {
					BlockPos pos = new BlockPos(x, y, z);
					if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
							&& !level.getBlockState(pos.below()).isAir()) {
						found.add(pos);
						break;
					}
				}
			}
		}
		found.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(heart)));
		return found.size() > wanted ? new ArrayList<>(found.subList(0, wanted)) : found;
	}

	private static @org.jspecify.annotations.Nullable String factionOf(String structurePath) {
		for (String faction : WarfrontRegistry.factions().keySet()) {
			if (structurePath.equals(faction) || structurePath.startsWith(faction + "_")) {
				return faction;
			}
		}
		return null;
	}

	private static String tierOf(String structurePath) {
		if (structurePath.contains("headquarters")) {
			return "headquarters";
		}
		if (structurePath.endsWith("_metropolis")) {
			return "metropolis";
		}
		if (structurePath.endsWith("_city")) {
			return "city";
		}
		if (structurePath.endsWith("_town")) {
			return "town";
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

	/** Repairs garrison membership written by older builds when a soldier next loads. */
	public static void validateMembership(SoldierEntity soldier) {
		if (!(soldier.level() instanceof ServerLevel level) || soldier.getBaseKey().isEmpty()) {
			return;
		}
		WarfrontState state = WarfrontState.get(level.getServer());
		WarfrontState.Base base = state.base(soldier.getBaseKey());
		if (base != null && base.faction.equals(soldier.getFaction())) {
			return;
		}
		if (base != null && base.garrison > 0) {
			base.garrison--;
		}
		soldier.setBaseKey("");
		state.markBasesDirty();
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
			refreshHousing(level, key, base);
			if (level.getGameTime() - base.lastReinforce >= DISCOVERY_SETTLE_TICKS) {
				reconcileLoadedGarrison(level, key, base, target);
			}
			if (!base.hydrated) {
				// Let every template-embedded seed entity load and adopt before topping up.
				// Without this window, a large HQ can hydrate mid-chunk-load and duplicate
				// the late seeds, overshooting both its target and the global cap.
				if (level.getGameTime() - base.lastReinforce < DISCOVERY_SETTLE_TICKS) {
					continue;
				}
				budget -= hydrate(level, key, base, target, budget);
				base.hydrated = base.garrison >= target;
				state.markBasesDirty();
			} else {
				budget -= reinforce(level, key, base, faction, target, budget);
			}
		}
	}

	/**
	 * Repairs old ledgers and removes only excess same-faction soldiers created by the
	 * former mid-load hydration race. This runs solely while the base is loaded and a
	 * player is close enough for every garrison entity to be represented.
	 */
	private static void reconcileLoadedGarrison(ServerLevel level, String key, WarfrontState.Base base, int target) {
		List<SoldierEntity> live = liveGarrison(level, key);
		int removedWrongFaction = 0;
		boolean changed = false;
		for (SoldierEntity soldier : List.copyOf(live)) {
			if (!base.faction.equals(soldier.getFaction())) {
				soldier.setBaseKey("");
				live.remove(soldier);
				removedWrongFaction++;
			}
		}
		if (removedWrongFaction > 0) {
			base.garrison = Math.max(0, base.garrison - removedWrongFaction);
			changed = true;
		}
		if (live.size() > target) {
			// Keep officers where possible; trim ordinary duplicate soldiers first.
			live.sort(java.util.Comparator.comparingInt(soldier ->
					"officer".equals(soldier.getRank()) || "quartermaster".equals(soldier.getRank()) ? 1 : 0));
			int excess = live.size() - target;
			for (int i = 0; i < excess; i++) {
				SoldierEntity soldier = live.get(i);
				io.github.lilkuzcodev.warfront.entity.StationManager.release(soldier);
				io.github.lilkuzcodev.warfront.dialogue.DialogueSessions.onSoldierGone(soldier);
				soldier.setBaseKey("");
				soldier.discard();
			}
			live = live.subList(excess, live.size());
			changed = true;
		}
		// Never lower a virtual ledger merely because an entity's chunk has not loaded
		// yet. Reaching target live count proves the whole target is represented.
		if (live.size() >= target && base.garrison != target) {
			base.garrison = target;
			changed = true;
		}
		if (changed) {
			WarfrontState.get(level.getServer()).markBasesDirty();
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
	private static int hydrate(ServerLevel level, String key, WarfrontState.Base base, int target, int maxSpawn) {
		int live = liveGarrison(level, key).size();
		base.garrison = live;
		int spawned = 0;
		List<BlockPos> anchors = anchorPoints(level, base);
		while (base.garrison < target && spawned < target && spawned < maxSpawn) {
			// one officer per ~8: rank rolled from current count
			if (spawnSoldier(level, key, base, anchors, base.garrison % 8 == 7 ? "officer" : "soldier") == null) {
				break;
			}
			spawned++;
		}
		return spawned;
	}

	/** The bunk-driven respawn cycle: one soldier per bunk per interval, up to the tier target. */
	private static int reinforce(ServerLevel level, String key, WarfrontState.Base base, Faction faction, int target,
			int maxSpawn) {
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
		for (int i = 0; i < bunks.size() && base.garrison < target && spawned < maxSpawn; i++) {
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
		if (soldier == null) {
			return null;
		}
		soldier.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		soldier.setFaction(base.faction);
		soldier.setRank(rank);
		soldier.setHomePos(anchor);
		soldier.setBaseKey(key);
		soldier.applyLoadout(WarfrontState.get(level.getServer()).techLevel(base.faction));
		soldier.setPersistenceRequired();
		if (!level.addFreshEntity(soldier)) {
			return null;
		}
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

	public static int loadedGarrisonCount(ServerLevel level, String key) {
		return liveGarrison(level, key).size();
	}

	/**
	 * Spawn anchors, distributed across the base's pieces: bunks (barracks), stations
	 * (towers, gates), falling back to the center courtyard. This is what puts soldiers
	 * on the towers and at the gates instead of one clump in the middle.
	 */
	private static List<BlockPos> anchorPoints(ServerLevel level, WarfrontState.Base base) {
		// Bounded to the core deliberately. This runs on EVERY base tick until the base
		// finishes hydrating, and a jigsaw structure's bounding box is far larger than
		// the plate: a generated city measured 245x46x245, so the unbounded pair of
		// scans was 5.5M block reads every fifteen seconds for any base that could not
		// finish hydrating. Anchors only need to be somewhere sensible to stand.
		List<BlockPos> anchors = scanNearCentre(level, base, WarfrontBlocks.BUNK, ANCHOR_SCAN_RADIUS);
		anchors.addAll(scanNearCentre(level, base, WarfrontBlocks.SANDBAG_STATION, ANCHOR_SCAN_RADIUS));
		if (anchors.isEmpty()) {
			anchors.add(base.center);
		}
		return anchors;
	}

	/** {@link #scanBounds} clipped to a box around the base centre. */
	private static List<BlockPos> scanNearCentre(ServerLevel level, WarfrontState.Base base,
			net.minecraft.world.level.block.Block block, int radius) {
		List<BlockPos> found = new ArrayList<>();
		List<Integer> b = base.bounds;
		if (b.size() != 6) {
			return found;
		}
		int minX = Math.max(b.get(0), base.center.getX() - radius);
		int maxX = Math.min(b.get(3), base.center.getX() + radius);
		int minZ = Math.max(b.get(2), base.center.getZ() - radius);
		int maxZ = Math.min(b.get(5), base.center.getZ() + radius);
		for (BlockPos pos : BlockPos.betweenClosed(minX, b.get(1), minZ, maxX, b.get(4), maxZ)) {
			if (level.getBlockState(pos).is(block)) {
				found.add(pos.immutable());
			}
		}
		return found;
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
