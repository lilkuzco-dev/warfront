package io.github.lilkuzcodev.warfront.systems;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Minimal delayed-task queue on the server tick. */
public final class TickScheduler {
	private record Task(long runAtTick, Runnable action) {
	}

	private static final List<Task> TASKS = new ArrayList<>();
	private static long tick;

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			Iterator<Task> iterator = TASKS.iterator();
			while (iterator.hasNext()) {
				Task task = iterator.next();
				if (tick >= task.runAtTick()) {
					iterator.remove();
					task.action().run();
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
