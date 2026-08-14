package io.github.lilkuzcodev.warfront.order;

import io.github.lilkuzcodev.warfront.Warfront;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * Abstract (off-screen) battle resolution seam (architecture note 4). Execution logic
 * calls this instead of touching entities when the theater is unloaded; the Phase 1
 * implementation is an explicit no-op that reports cleanly — never frozen entities.
 */
public interface VirtualResolver {
	VirtualResolver NOOP = (server, order, report) -> {
		String message = "Order " + order.objectiveType() + " for " + order.faction() + " at " + order.target().toShortString()
				+ " targets unloaded chunks — virtual resolution not yet implemented; order aborted cleanly.";
		Warfront.LOGGER.info(message);
		report.accept(message);
	};

	void resolve(MinecraftServer server, Order order, Consumer<String> report);
}
