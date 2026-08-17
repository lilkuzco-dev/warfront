package io.github.lilkuzcodev.warfront.systems;

import io.github.lilkuzcodev.warfront.Warfront;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Minimal delayed-task queue on the server tick. */
public final class TickScheduler {
	private record Task(long runAtTick, Runnable action) {
	}

	private static final List<Task> TASKS = new ArrayList<>();
	private static long tick;

	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TASKS.clear();
			tick = 0;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			Iterator<Task> iterator = TASKS.iterator();
			while (iterator.hasNext()) {
				Task task = iterator.next();
				if (tick >= task.runAtTick()) {
					iterator.remove();
					try {
						task.action().run();
					} catch (RuntimeException exception) {
						Warfront.LOGGER.error("Scheduled Warfront task failed", exception);
					}
				}
			}
		});
	}

	public static void schedule(int delayTicks, Runnable action) {
		TASKS.add(new Task(tick + delayTicks, action));
	}

	private TickScheduler() {
	}
}
