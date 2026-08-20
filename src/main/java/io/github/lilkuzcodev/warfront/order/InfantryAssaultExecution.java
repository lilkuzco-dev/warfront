package io.github.lilkuzcodev.warfront.order;

import io.github.lilkuzcodev.warfront.data.Doctrine;
import io.github.lilkuzcodev.warfront.data.Faction;
import io.github.lilkuzcodev.warfront.data.TacticalTemplate;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import io.github.lilkuzcodev.warfront.entity.SquadManager;
import io.github.lilkuzcodev.warfront.entity.WarfrontEntities;
import io.github.lilkuzcodev.warfront.systems.TickScheduler;
import io.github.lilkuzcodev.warfront.systems.WarfrontSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * The one shipped tactical template executor: muster -> approach via doctrine vectors ->
 * engage -> retreat at doctrine threshold (RetreatGoal) -> report outcome.
 *
 * <p>Doctrine differences are deliberately visible:
 * Vostok masses one axis in two echeloned waves; Aegis splits into multiple flank
 * vectors; Sarab infiltrates close and springs from ambush range.
 */
public final class InfantryAssaultExecution implements General.TemplateExecutor {
	@Override
	public void execute(MinecraftServer server, Order order, Faction faction, TacticalTemplate template, Consumer<String> report) {
		ServerLevel level = server.overworld();
		BlockPos target = order.target();
		if (!level.hasChunk(target.getX() >> 4, target.getZ() >> 4)) {
			// architecture note 4: never touch entities in unloaded space
			VirtualResolver.NOOP.resolve(server, order, report);
			return;
		}
		WarfrontState state = WarfrontState.get(server);
		Doctrine doctrine = faction.doctrine();
		int techLevel = state.techLevel(faction.id());
		int squadBonus = WarfrontRegistry.tech().squadBonusByLevel().getOrDefault(techLevel, 0);
		int size = doctrine.preferredSquadSize() + Math.round(squadBonus * doctrine.squadGrowth());
		if (order.forceCap() > 0) {
			size = Math.min(size, order.forceCap());
		}
		size = Math.min(size, Math.max(0, WarfrontSystems.soldierBudget(level)));
		if (size == 0) {
			report.accept("Assault aborted: the global live-soldier cap is already full.");
			return;
		}

		double baseAngle = level.getRandom().nextDouble() * Math.PI * 2;
		int vectors = Math.max(1, doctrine.flankVectors());
		double musterDistance = doctrine.ambushBias() >= 0.5F ? 18.0 : 45.0; // Sarab infiltrates close
		UUID squadId = SquadManager.createSquad(faction.id(), size, target);

		List<SoldierEntity> spawned = new ArrayList<>();
		boolean echeloned = doctrine.casualtyTolerance() >= 0.6F && vectors == 1; // Vostok wave assault
		int firstWave = echeloned ? (size + 1) / 2 : size;
		for (int i = 0; i < firstWave; i++) {
			SoldierEntity soldier = spawnAttacker(level, target, baseAngle, vectors, i, musterDistance,
					faction, squadId, techLevel, i == 0);
			if (soldier != null) {
				spawned.add(soldier);
			}
		}
		if (echeloned) {
			int remaining = size - firstWave;
			TickScheduler.schedule(200, () -> {
				for (int i = 0; i < remaining && WarfrontSystems.soldierBudget(level) > 0; i++) {
					spawnAttacker(level, target, baseAngle, 1, i, musterDistance + 10, faction, squadId, techLevel, false);
				}
			});
		}
		if (spawned.isEmpty()) {
			report.accept("Assault aborted: no safe, loaded spawn position was available.");
			return;
		}

		String style = echeloned ? "echeloned waves (" + firstWave + "+" + (size - firstWave) + ")"
				: doctrine.ambushBias() >= 0.5F ? "infiltration from ambush range (" + vectors + " approaches)"
				: vectors > 1 ? vectors + " flank vectors" : "single axis";
		report.accept(faction.name() + " assault on " + target.toShortString() + ": " + size + " soldiers, " + style
				+ ", retreat at " + Math.round(doctrine.retreatThreshold() * 100) + "% losses");

		// outcome report after the template's estimated duration
		TickScheduler.schedule(template.costTimeTicks(), () -> {
			long survivors = level.getEntitiesOfClass(SoldierEntity.class,
					new net.minecraft.world.phys.AABB(target).inflate(96, 64, 96),
					s -> squadId.equals(s.getSquadId()) && s.isAlive()).size();
			float losses = SquadManager.lossFraction(squadId);
			String outcome = "Assault report [" + faction.name() + " @ " + target.toShortString() + "]: "
					+ survivors + " in theater, " + Math.round(losses * 100) + "% losses"
					+ (losses >= doctrine.retreatThreshold() ? " — withdrew per doctrine" : "");
			server.sendSystemMessage(net.minecraft.network.chat.Component.literal(outcome));
		});
	}

	private @org.jspecify.annotations.Nullable SoldierEntity spawnAttacker(ServerLevel level, BlockPos target,
			double baseAngle, int vectors, int index,
			double distance, Faction faction, UUID squadId, int techLevel, boolean officer) {
		double angle = baseAngle + (vectors > 1 ? (index % vectors) * (Math.PI * 2 / vectors) : 0)
				+ (level.getRandom().nextDouble() - 0.5) * 0.3;
		int x = target.getX() + (int) Math.round(Math.cos(angle) * distance);
		int z = target.getZ() + (int) Math.round(Math.sin(angle) * distance);
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return null;
		}
		BlockPos spawn = io.github.lilkuzcodev.warfront.systems.SpawnSafety.openGroundNear(level, x, z, 5);
		if (spawn == null) return null;
		SoldierEntity soldier = WarfrontEntities.SOLDIER.create(level, EntitySpawnReason.EVENT);
		if (soldier == null) {
			return null;
		}
		soldier.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		soldier.setFaction(faction.id());
		soldier.setRank(officer ? "officer" : "soldier");
		soldier.setHomePos(target); // anchors approach + post-fight patrol on the objective
		soldier.applyLoadout(techLevel);
		soldier.setPersistenceRequired();
		if (!level.addFreshEntity(soldier)) {
			return null;
		}
		SquadManager.join(squadId, soldier);
		soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.1);
		return soldier;
	}
}
