package io.github.lilkuzcodev.warfront.order;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.data.Doctrine;
import io.github.lilkuzcodev.warfront.data.Faction;
import io.github.lilkuzcodev.warfront.data.TacticalTemplate;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * The stub planner (architecture note 3): receives an Order, filters the tactical
 * template registry by preconditions (tech gate, assets, intel, constraints), scores
 * survivors by doctrine affinity, and hands the winner to its executor. Phase 1 ships
 * one executor ("warfront:infantry_assault"); future templates that reuse an existing
 * executor are pure data.
 */
public final class General {
	private static final Map<String, TemplateExecutor> EXECUTORS = new HashMap<>();

	public interface TemplateExecutor {
		void execute(MinecraftServer server, Order order, Faction faction, TacticalTemplate template, Consumer<String> report);
	}

	public static void registerExecutor(String id, TemplateExecutor executor) {
		EXECUTORS.put(id, executor);
	}

	public static void submit(MinecraftServer server, Order order, Consumer<String> report) {
		Faction faction = WarfrontRegistry.faction(order.faction());
		if (faction == null) {
			report.accept("Unknown faction: " + order.faction());
			return;
		}
		int techLevel = WarfrontState.get(server).techLevel(order.faction());
		Doctrine doctrine = faction.doctrine();

		TacticalTemplate best = null;
		double bestScore = -Double.MAX_VALUE;
		for (TacticalTemplate template : WarfrontRegistry.templates().values()) {
			if (template.minTechLevel() > techLevel) {
				continue; // the universal capability gate (architecture note 5)
			}
			if (!template.requiredIntel().isEmpty() && !order.knownIntel().containsAll(template.requiredIntel())) {
				continue;
			}
			if (!template.constraintCompatibility().containsAll(order.constraints())) {
				continue;
			}
			if (!EXECUTORS.containsKey(template.executor())) {
				continue;
			}
			// doctrine-weighted score; intel is all-estimates in Phase 1 (field exists, read here)
			double score = template.aggressionAffinity() * doctrine.aggression()
					+ template.ambushAffinity() * doctrine.ambushBias()
					- template.costSoldiers() * 0.01
					+ order.knownIntel().size() * 0.05;
			if (score > bestScore) {
				bestScore = score;
				best = template;
			}
		}
		if (best == null) {
			report.accept("No tactical template satisfies the order (tech level " + techLevel + ").");
			return;
		}
		Warfront.LOGGER.info("General[{}]: order {} -> template {} (score {})", order.faction(), order.objectiveType(), best.id(), bestScore);
		report.accept(faction.name() + " general selected template '" + best.id() + "'");
		EXECUTORS.get(best.executor()).execute(server, order, faction, best, report);
	}

	private General() {
	}
}
