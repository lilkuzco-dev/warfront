import io.github.lilkuzcodev.warfront.civilization.EconomyModel;
import io.github.lilkuzcodev.warfront.civilization.EconomyModel.Good;

public class Harness {
	static int failures = 0;
	static void check(String what, boolean ok) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
		if (!ok) failures++;
	}
	static void conserved(EconomyModel m, String where) {
		check(where + ": conservation closes", m.conservation().balanced());
	}

	public static void main(String[] args) {
		var cfg = new EconomyModel.Config(20, 12345L, 1000L, 100L, 3L, 20, 400, 180);
		var m = new EconomyModel(cfg);
		m.advance(200);
		conserved(m, "after 200 ticks");
		check("20 active actors", m.activeCount() == 20);

		// --- births ---
		long before = m.conservation().moneyNow() + m.treasury();
		m.bringActorToLife(20, 300L);
		m.bringActorToLife(21, 300L);
		conserved(m, "after two births");
		check("22 active actors", m.activeCount() == 22);
		check("births move money, never mint it",
			m.conservation().moneyNow() + m.treasury() == before);
		check("newborn is above the liquidity floor", m.actorMoney(20) >= 100L);

		// --- deaths ---
		long estateBefore = m.treasury();
		long deadMoney = m.actorMoney(5);
		m.retireActor(5);
		conserved(m, "after a death");
		check("21 active actors", m.activeCount() == 21);
		check("the estate goes to the treasury", m.treasury() == estateBefore + deadMoney);
		check("a retired slot reports inactive", !m.isActive(5));

		m.advance(300);
		conserved(m, "after 300 more ticks with a dead slot");

		// --- the open economy ---
		long inBefore = m.conservation().externalMoneyIn();
		m.depositExternal(5000L);
		conserved(m, "after an expedition deposit");
		check("mined wealth is booked as external inflow",
			m.conservation().externalMoneyIn() == inBefore + 5000L);
		check("mined wealth lands in the treasury", m.treasury() >= 5000L);

		long taken = m.withdrawExternal(2000L);
		conserved(m, "after being looted");
		check("loot leaves the city", taken == 2000L);

		long foodBefore = m.foodHeld();
		m.depositGoods(Good.FOOD, 64L);
		conserved(m, "after foraged goods arrive");
		check("foraged food is held", m.foodHeld() == foodBefore + 64L);
		long removed = m.removeGoods(Good.FOOD, 30L);
		conserved(m, "after goods are looted away");
		check("looted goods leave", removed == 30L && m.foodHeld() == foodBefore + 34L);

		// looting more than exists must not go negative
		long all = m.foodHeld();
		long over = m.removeGoods(Good.FOOD, all + 10_000L);
		conserved(m, "after over-looting");
		check("over-looting is clamped", over == all && m.foodHeld() == 0);

		m.advance(200);
		conserved(m, "after 200 ticks post-plunder");

		// --- persistence round trip (the bug: initialMoney re-derived from a grown pop) ---
		String encoded = m.encode();
		EconomyModel back = EconomyModel.decode(encoded);
		conserved(back, "decoded snapshot");
		check("population survives the round trip", back.population() == m.population());
		check("active count survives", back.activeCount() == m.activeCount());
		check("treasury survives", back.treasury() == m.treasury());
		check("dead stay dead", !back.isActive(5));
		check("re-encode is byte-identical", back.encode().equals(encoded));
		back.advance(100);
		conserved(back, "decoded model still runs");

		System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURES");
		System.exit(failures == 0 ? 0 : 1);
	}
}
