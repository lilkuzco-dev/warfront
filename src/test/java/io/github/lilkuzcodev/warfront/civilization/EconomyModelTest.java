package io.github.lilkuzcodev.warfront.civilization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EconomyModelTest {
	@Test
	void equalStartProducesPoorMiddleAndEliteByTenThousandTicks() {
		EconomyModel model = new EconomyModel(EconomyModel.Config.validation(0x5EEDC1A7L));
		print(model.distribution());
		for (int checkpoint : new int[] { 100, 900, 4_000, 5_000 }) {
			model.advance(checkpoint);
			print(model.distribution());
		}
		EconomyModel.Distribution result = model.distribution();
		assertEquals(10_000, result.tick());
		assertTrue(result.gini() > 0.40, "inequality did not emerge: " + result);
		assertTrue(result.poorShare() >= 0.30 && result.poorShare() <= 0.70,
				"meaningful poverty-trapped class missing: " + result);
		assertTrue(result.topFiveShare() >= 0.12, "rich tail missing: " + result);
		assertTrue(result.upperQuartile() > result.median(), "middle/upper strata collapsed: " + result);
		assertTrue(model.conservation().balanced(), model.conservation().toString());
	}

	@Test
	void sameSeedAndTicksProduceGoldenIdenticalState() {
		EconomyModel first = new EconomyModel(EconomyModel.Config.validation(99L));
		EconomyModel replay = new EconomyModel(EconomyModel.Config.validation(99L));
		first.advance(10_000);
		replay.advance(10_000);
		assertEquals(first.distribution(), replay.distribution());
		assertEquals(first.conservation(), replay.conservation());
		assertArrayEquals(first.moneySnapshot(), replay.moneySnapshot());
		assertArrayEquals(first.netWorthSnapshot(), replay.netWorthSnapshot());
		EconomyModel restored = EconomyModel.decode(first.encode());
		assertEquals(first.distribution(), restored.distribution());
		assertEquals(first.conservation(), restored.conservation());
		assertArrayEquals(first.moneySnapshot(), restored.moneySnapshot());
	}

	@Test
	void depletionAndBlightReshuffleFortunes() {
		var config = new EconomyModel.Config(250, 77123L, 1_000L, 100L, 3L, 250, 0, 300);
		EconomyModel baseline = new EconomyModel(config);
		EconomyModel shocked = new EconomyModel(config);
		baseline.advance(10_000);
		shocked.advance(5_000);
		shocked.injectShock(EconomyModel.Shock.VEIN_DEPLETION);
		shocked.injectShock(EconomyModel.Shock.BLIGHT);
		shocked.advance(5_000);

		int[] baseRanks = ranks(baseline.netWorthSnapshot());
		int[] shockRanks = ranks(shocked.netWorthSnapshot());
		int moved = 0;
		int rose = 0;
		int fell = 0;
		for (int actor = 0; actor < baseRanks.length; actor++) {
			int delta = shockRanks[actor] - baseRanks[actor];
			if (Math.abs(delta) >= 10) moved++;
			if (delta >= 10) rose++;
			if (delta <= -10) fell++;
		}
		System.out.printf("shockRanks moved=%d rose=%d fell=%d baselineGini=%.4f shockedGini=%.4f%n",
				moved, rose, fell, baseline.distribution().gini(), shocked.distribution().gini());
		assertTrue(moved >= 25 && rose > 0 && fell > 0,
				"shock caused a uniform dip instead of rank mobility");
		assertTrue(shocked.conservation().balanced(), shocked.conservation().toString());
	}

	@Test
	void localScarcityCreatesRegionalPrices() {
		var config = new EconomyModel.Config(250, 48271L, 1_000L, 100L, 3L, 250, 0, 650);
		EconomyModel stable = new EconomyModel(config);
		EconomyModel disrupted = new EconomyModel(config);
		stable.advance(600);
		disrupted.advance(300);
		disrupted.injectShock(EconomyModel.Shock.BLIGHT);
		disrupted.injectShock(EconomyModel.Shock.VEIN_DEPLETION);
		disrupted.advance(300);

		boolean diverged = Arrays.stream(EconomyModel.Good.values())
				.anyMatch(good -> stable.price(good) != disrupted.price(good));
		System.out.printf("regionalPrices stableFood=%d disruptedFood=%d stableOre=%d disruptedOre=%d%n",
				stable.price(EconomyModel.Good.FOOD), disrupted.price(EconomyModel.Good.FOOD),
				stable.price(EconomyModel.Good.ORE), disrupted.price(EconomyModel.Good.ORE));
		assertTrue(diverged, "local scarcity failed to create a regional price difference");
		assertTrue(disrupted.conservation().balanced(), disrupted.conservation().toString());
	}

	@Test
	void fiveHundredCitizenTickRemainsBelowOneMillisecondAverage() {
		var config = new EconomyModel.Config(500, 918273L, 1_000L, 100L, 3L, 500, 400, 180);
		EconomyModel model = new EconomyModel(config);
		model.advance(200); // warm the JIT before measuring
		long started = System.nanoTime();
		model.advance(2_000);
		double averageNanos = (System.nanoTime() - started) / 2_000.0;
		System.out.printf("economyPerf population=500 average=%.3fms%n", averageNanos / 1_000_000.0);
		assertTrue(averageNanos < 1_000_000.0, "500-citizen tick exceeded 1 ms: " + averageNanos);
		assertTrue(model.conservation().balanced(), model.conservation().toString());
	}

	@Test
	void embodiedGoodsReconcileWithoutEconomicFreeEnergy() {
		EconomyModel model = new EconomyModel(new EconomyModel.Config(5, 7L, 1_000L, 100L, 3L, 5, 0, 0));
		model.setActorGoods(0, Map.of(EconomyModel.Good.ORE, 4L, EconomyModel.Good.TIMBER, 2L));
		assertTrue(model.conservation().balanced(), model.conservation().toString());
		model.setActorGoods(0, Map.of(EconomyModel.Good.ORE, 1L, EconomyModel.Good.TIMBER, 1L,
				EconomyModel.Good.CRAFTS, 2L));
		assertTrue(model.conservation().balanced(), model.conservation().toString());
		assertEquals(8L, model.conservation().regenerated());
		assertEquals(4L, model.conservation().consumed());
	}

	private static int[] ranks(long[] values) {
		Integer[] order = new Integer[values.length];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, (a, b) -> Long.compare(values[a], values[b]));
		int[] ranks = new int[values.length];
		for (int rank = 0; rank < order.length; rank++) ranks[order[rank]] = rank;
		return ranks;
	}

	private static void print(EconomyModel.Distribution d) {
		System.out.printf("economy tick=%d gini=%.4f poor=%.1f%% top5=%.1f%% min=%d q25=%d median=%d q75=%d p90=%d max=%d money=%d goods=%d unmet=%d%n",
				d.tick(), d.gini(), d.poorShare() * 100, d.topFiveShare() * 100, d.minimum(),
				d.lowerQuartile(), d.median(), d.upperQuartile(), d.p90(), d.maximum(), d.totalMoney(),
				d.totalGoods(), d.unmetUpkeep());
	}
}
