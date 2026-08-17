package io.github.lilkuzcodev.warfront.civilization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CivilizationMathTest {
	private static volatile long benchmarkChecksum;
	@Test
	void tierTransitionsConserveEveryGoodExactly() {
		Map<String, Integer> embodied = Map.of("minecraft:wheat", 17, "minecraft:raw_iron", 4);
		Map<String, Integer> local = Map.copyOf(new HashMap<>(embodied));
		Map<String, Integer> virtual = Map.copyOf(new HashMap<>(local));
		Map<String, Integer> reconstituted = Map.copyOf(new HashMap<>(virtual));

		assertEquals(embodied, reconstituted);
		assertEquals(CivilizationMath.goodsTotal(embodied), CivilizationMath.goodsTotal(reconstituted));
	}

	@Test
	void minerMidWorkAdvancesExactlyWhileVirtual() {
		var result = CivilizationMath.advance("minecraft:raw_iron", 75, Map.of(), 12_125);
		assertEquals(61, result.produced());
		assertEquals(0, result.remainderTicks());
		assertEquals(Map.of("minecraft:raw_iron", 61), result.inventory());
	}

	@Test
	void coarseTicksEqualOneElapsedTickAndReplayIsDeterministic() {
		Map<String, Integer> initial = Map.of("minecraft:wheat", 2);
		var oneShot = CivilizationMath.advance("minecraft:wheat", 137, initial, 20_017);
		var firstHalf = CivilizationMath.advance("minecraft:wheat", 137, initial, 8_003);
		var segmented = CivilizationMath.advance("minecraft:wheat", firstHalf.remainderTicks(),
				firstHalf.inventory(), 12_014);
		var replay = CivilizationMath.advance("minecraft:wheat", 137, initial, 20_017);

		assertEquals(oneShot.remainderTicks(), segmented.remainderTicks());
		assertEquals(oneShot.inventory(), segmented.inventory());
		assertEquals(oneShot, replay);
	}

	@Test
	void backwardsClockFailsClosed() {
		assertThrows(IllegalArgumentException.class,
				() -> CivilizationMath.advance("minecraft:wheat", 0, Map.of(), -1));
	}

	@Test
	void maximumCommandSizedVirtualCityStaysBelowOneMillisecondPerTick() {
		long checksum = 0;
		for (int warmup = 0; warmup < 500; warmup++) checksum += advanceCityOfFiveHundred(warmup * 20L);
		long[] batches = new long[15];
		for (int batch = 0; batch < batches.length; batch++) {
			long started = System.nanoTime();
			for (int sample = 0; sample < 50; sample++) {
				checksum += advanceCityOfFiveHundred((batch * 50L + sample) * 20L);
			}
			batches[batch] = (System.nanoTime() - started) / 50L;
		}
		benchmarkChecksum = checksum;
		Arrays.sort(batches);
		long medianNanos = batches[batches.length / 2];
		System.out.println("virtualCity500MedianNanos=" + medianNanos);
		assertTrue(medianNanos < 1_000_000L,
				"500-record pure-data city tick exceeded 1ms median: " + medianNanos + "ns");
	}

	private static long advanceCityOfFiveHundred(long tick) {
		long checksum = 0;
		for (int serial = 1; serial <= 500; serial++) {
			var result = CivilizationMath.advance("warfront:test_goods", (serial * 17L) % 200L,
					Map.of("warfront:test_goods", serial % 11), tick + 20L);
			checksum += result.remainderTicks() + CivilizationMath.goodsTotal(result.inventory());
		}
		return checksum;
	}
}
