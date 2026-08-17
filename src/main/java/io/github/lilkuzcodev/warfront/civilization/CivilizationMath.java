package io.github.lilkuzcodev.warfront.civilization;

import java.util.HashMap;
import java.util.Map;

/** Pure arithmetic shared by local-abstract and virtual citizens. */
public final class CivilizationMath {
	public static final long WORK_CYCLE_TICKS = 200L;

	public record WorkResult(long remainderTicks, Map<String, Integer> inventory, long produced) {}

	public static WorkResult advance(CitizenProfession profession, long workTicks,
			Map<String, Integer> inventory, long elapsedTicks) {
		return advance(profession.abstractOutput(), workTicks, inventory, elapsedTicks);
	}

	public static WorkResult advance(String outputId, long workTicks,
			Map<String, Integer> inventory, long elapsedTicks) {
		if (elapsedTicks < 0 || workTicks < 0) {
			throw new IllegalArgumentException("economic clocks cannot run backwards");
		}
		long total = Math.addExact(workTicks, elapsedTicks);
		long cycles = total / WORK_CYCLE_TICKS;
		Map<String, Integer> next = new HashMap<>(inventory);
		if (cycles > 0) {
			int produced = Math.toIntExact(cycles);
			next.merge(outputId, produced, Math::addExact);
		}
		return new WorkResult(total % WORK_CYCLE_TICKS, Map.copyOf(next), cycles);
	}

	public static long goodsTotal(Map<String, Integer> inventory) {
		return inventory.values().stream().mapToLong(Integer::longValue).sum();
	}

	private CivilizationMath() {}
}
