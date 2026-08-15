package io.github.lilkuzcodev.warfront.systems;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.block.WarfrontBlocks;
import io.github.lilkuzcodev.warfront.data.Faction;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import io.github.lilkuzcodev.warfront.entity.SquadManager;
import io.github.lilkuzcodev.warfront.entity.WarfrontEntities;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;

/**
 * Server-side systems: player hostility (shared per-faction standing + decay), faction
 * tech-point accrual, and the roaming patrol spawner near faction bases.
 */
public final class WarfrontSystems {
	private static final int MINUTE_TICKS = 1200;
	private static final int PATROL_INTERVAL = 2400;

	public static final TagKey<Structure> VOSTOK_BASES = TagKey.create(Registries.STRUCTURE, Warfront.id("vostok_bases"));
	public static final TagKey<Structure> AEGIS_BASES = TagKey.create(Registries.STRUCTURE, Warfront.id("aegis_bases"));
	public static final TagKey<Structure> SARAB_BASES = TagKey.create(Registries.STRUCTURE, Warfront.id("sarab_bases"));

	public static void init() {
		// attacking any faction member -> faction-wide standing penalty for that player
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (blocked || !(entity instanceof SoldierEntity soldier) || soldier.getFaction().isEmpty()) {
				return;
			}
			if (source.getEntity() instanceof ServerPlayer player) {
				WarfrontState state = WarfrontState.get(player.level().getServer());
				state.addStanding(player.getUUID(), soldier.getFaction(),
						WarfrontRegistry.standing().attackPenalty());
				state.recordEvent(player.getUUID(), soldier.getFaction(), "attacked_soldier",
						WarfrontState.clock(player.level()));
			}
		});
		// damaging base infrastructure (station blocks, banners near soldiers) -> penalty
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
				return;
			}
			boolean baseBlock = state.is(WarfrontBlocks.SANDBAG_STATION) || state.is(WarfrontBlocks.BUNK)
					|| state.getBlock().getDescriptionId().contains("banner");
			if (!baseBlock) {
				return;
			}
			List<SoldierEntity> nearby = serverLevel.getEntitiesOfClass(SoldierEntity.class,
					new AABB(pos).inflate(32), s -> !s.getFaction().isEmpty());
			if (!nearby.isEmpty()) {
				String faction = nearby.get(0).getFaction();
				WarfrontState warfrontState = WarfrontState.get(serverLevel.getServer());
				warfrontState.addStanding(serverPlayer.getUUID(), faction, WarfrontRegistry.standing().blockPenalty());
				warfrontState.recordEvent(serverPlayer.getUUID(), faction, "destroyed_property",
						WarfrontState.clock(serverLevel));
			}
		});
		// once a minute: standing decay toward neutral + tech point accrual per doctrine rate
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % MINUTE_TICKS != 0) {
				return;
			}
			WarfrontState state = WarfrontState.get(server);
			state.decayStandings(WarfrontRegistry.standing().decayPerMinute());
			state.pruneLedger(WarfrontState.clock(server.overworld()));
			double perMinuteBase = WarfrontRegistry.tech().pointsPerDay() * MINUTE_TICKS / 24000.0;
			for (Faction faction : WarfrontRegistry.factions().values()) {
				state.addPoints(faction.id(), perMinuteBase * faction.doctrine().techRate());
			}
		});
		// inter-base roaming squads: shuttles between friendly bases within link range
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int interval = Math.max(1, WarfrontRegistry.population().roamIntervalSeconds()) * 20;
			if (server.getTickCount() % interval != 0) {
				return;
			}
			ServerLevel level = server.overworld();
			if (level.getRandom().nextFloat() > WarfrontRegistry.population().roamChance()) {
				return;
			}
			spawnInterBaseSquad(level);
		});
		// roaming squads in faction territory (within ~200 blocks of a base)
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % PATROL_INTERVAL != 0) {
				return;
			}
			ServerLevel level = server.overworld();
			for (ServerPlayer player : level.players()) {
				if (level.getRandom().nextFloat() > 0.4F) {
					continue;
				}
				String faction = switch (level.getRandom().nextInt(3)) {
					case 0 -> "vostok";
					case 1 -> "aegis";
					default -> "sarab";
				};
				TagKey<Structure> tag = switch (faction) {
					case "vostok" -> VOSTOK_BASES;
					case "aegis" -> AEGIS_BASES;
					default -> SARAB_BASES;
				};
				BlockPos base = level.findNearestMapStructure(tag, player.blockPosition(), 16, false);
				if (base == null || !base.closerThan(player.blockPosition(), 200)) {
					continue;
				}
				spawnRoamingSquad(level, faction, base);
			}
		});
	}

	/**
	 * Picks a friendly base pair within link range (one end near a player, so the squad
	 * is seen) and spawns a doctrine-flavored traveling squad: Vostok road-march columns
	 * (large, in file), Aegis small cross-country teams, Sarab night infiltration pairs.
	 */
	static void spawnInterBaseSquad(ServerLevel level) {
		var state = WarfrontState.get(level.getServer());
		if (state.bases().size() < 2) {
			return;
		}
		var keys = new java.util.ArrayList<>(state.bases().keySet());
		java.util.Collections.shuffle(keys, new java.util.Random(level.getRandom().nextLong()));
		for (String keyA : keys) {
			var baseA = state.base(keyA);
			Faction faction = WarfrontRegistry.faction(baseA.faction);
			if (faction == null || !nearAnyPlayer(level, baseA.center, 192)) {
				continue;
			}
			var pop = faction.population();
			if ("night_pair".equals(pop.roamStyle()) && !isNight(level)) {
				continue;
			}
			for (String keyB : keys) {
				var baseB = state.base(keyB);
				if (keyA.equals(keyB) || !baseA.faction.equals(baseB.faction)
						|| !baseA.center.closerThan(baseB.center, pop.roamLinkBlocks())) {
					continue;
				}
				spawnTravelSquad(level, faction, baseA.center, baseB.center);
				return;
			}
		}
	}

	private static boolean nearAnyPlayer(ServerLevel level, BlockPos pos, int radius) {
		for (ServerPlayer player : level.players()) {
			if (player.blockPosition().closerThan(pos, radius)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isNight(ServerLevel level) {
		long dayTime = level.getOverworldClockTime() % 24000L;
		return dayTime >= 13000L && dayTime <= 23000L;
	}

	private static void spawnTravelSquad(ServerLevel level, Faction faction, BlockPos from, BlockPos to) {
		int size = Math.max(2, faction.population().roamSquadSize());
		if (size > soldierBudget(level)) {
			return;
		}
		int techLevel = WarfrontState.get(level.getServer()).techLevel(faction.id());
		UUID squad = SquadManager.createSquad(faction.id(), size, from);
		// column style spawns the squad in a file along the march direction
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		double len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
		boolean column = "column".equals(faction.population().roamStyle());
		for (int i = 0; i < size; i++) {
			int x = from.getX() + (column ? (int) (dx / len * (i + 2)) : level.getRandom().nextInt(5) - 2);
			int z = from.getZ() + (column ? (int) (dz / len * (i + 2)) : level.getRandom().nextInt(5) - 2);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			SoldierEntity soldier = WarfrontEntities.SOLDIER.create(level, EntitySpawnReason.EVENT);
			soldier.setPos(x + 0.5, y, z + 0.5);
			soldier.setFaction(faction.id());
			soldier.setRank(i == 0 ? "officer" : "soldier");
			soldier.setHomePos(from);
			soldier.setRoute(from, to);
			soldier.applyLoadout(techLevel);
			soldier.setPersistenceRequired();
			SquadManager.join(squad, soldier);
			level.addFreshEntity(soldier);
		}
	}

	/** Remaining head-room under the global per-player soldier cap. */
	static int soldierBudget(ServerLevel level) {
		int cap = WarfrontRegistry.population().perPlayerSoldierCap() * Math.max(1, level.players().size());
		return cap - level.getEntitiesOfClass(SoldierEntity.class,
				new AABB(-3.0E7, -512, -3.0E7, 3.0E7, 512, 3.0E7)).size();
	}

	/** Spawns a doctrine-sized roaming squad near a base that patrols outward. */
	public static int spawnRoamingSquad(ServerLevel level, String factionId, BlockPos base) {
		Faction faction = WarfrontRegistry.faction(factionId);
		if (faction == null) {
			return 0;
		}
		if (soldierBudget(level) <= 0) {
			return 0;
		}
		WarfrontState state = WarfrontState.get(level.getServer());
		int techLevel = state.techLevel(factionId);
		int bonus = WarfrontRegistry.tech().squadBonusByLevel().getOrDefault(techLevel, 0);
		int size = Math.max(2, faction.doctrine().preferredSquadSize() + Math.round(bonus * faction.doctrine().squadGrowth()));
		double angle = level.getRandom().nextDouble() * Math.PI * 2;
		int cx = base.getX() + (int) (Math.cos(angle) * 40);
		int cz = base.getZ() + (int) (Math.sin(angle) * 40);
		if (!level.hasChunkAt(new BlockPos(cx, 0, cz))) {
			return 0;
		}
		UUID squad = SquadManager.createSquad(factionId, size, base);
		for (int i = 0; i < size; i++) {
			int x = cx + level.getRandom().nextInt(5) - 2;
			int z = cz + level.getRandom().nextInt(5) - 2;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			SoldierEntity soldier = WarfrontEntities.SOLDIER.create(level, EntitySpawnReason.EVENT);
			soldier.setPos(x + 0.5, y, z + 0.5);
			soldier.setFaction(factionId);
			soldier.setRank(i == 0 ? "officer" : "soldier");
			soldier.setHomePos(new BlockPos(cx, y, cz));
			soldier.applyLoadout(techLevel);
			soldier.setPersistenceRequired();
			SquadManager.join(squad, soldier);
			level.addFreshEntity(soldier);
		}
		return size;
	}

	private WarfrontSystems() {
	}
}
