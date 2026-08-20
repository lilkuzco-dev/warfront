package io.github.lilkuzcodev.warfront.civilization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;

/**
 * Deterministic, allocation-light city economy used by both tests and Tier-3 state.
 * Money is closed; goods move from finite nodes, through inventories, to consumption.
 */
public final class EconomyModel {
	public enum Good { FOOD, ORE, TIMBER, CRAFTS }
	public enum Role { MINER, FARMER, BUILDER, TRADER, LABORER }
	public enum Shock { VEIN_DEPLETION, BLIGHT, RAID, FIRE }

	public record Config(int population, long seed, long startingWealth, long liquidityFloor,
			long fixedExchange, int exchangesPerTick, int shockInterval, int shockPermille) {
		public Config {
			if (population < 1 || startingWealth < 1 || liquidityFloor < 0 || fixedExchange < 1
					|| exchangesPerTick < 0 || shockInterval < 0 || shockPermille < 0 || shockPermille > 1_000) {
				throw new IllegalArgumentException("invalid economy model configuration");
			}
		}

		public static Config validation(long seed) {
			return new Config(250, seed, 1_000L, 100L, 3L, 250, 400, 180);
		}
	}

	public record Distribution(long tick, double gini, double poorShare, double topFiveShare,
			long minimum, long lowerQuartile, long median, long upperQuartile, long p90, long maximum,
			long totalMoney, long totalGoods, long unmetUpkeep) {}

	/**
	 * Money is closed <em>with respect to the city</em>. Player trade is the one opening,
	 * so it is accounted rather than ignored: every emerald a player pays in and every
	 * emerald a citizen pays out is booked here, and the balance still has to close.
	 */
	public record Conservation(long initialMoney, long moneyNow, long initialGoods, long regenerated,
			long consumed, long shockLoss, long goodsNow, long externalMoneyIn, long externalMoneyOut,
			long treasury) {
		public boolean balanced() {
			return initialMoney + externalMoneyIn - externalMoneyOut == moneyNow + treasury
					&& initialGoods + regenerated - consumed - shockLoss == goodsNow;
		}
	}

	private final Config config;
	// Per-actor arrays GROW as a city has children and are never re-indexed: an actor's
	// slot is `serial - 1` for the life of the city, so a death leaves an inert slot
	// rather than shifting everyone down and corrupting every stored index.
	private long[] money;
	private int[] skill;
	private int[] metabolism;
	private int[] aptitude;
	private Role[] professions;
	private long[][] goods;
	private int[] assignedNode;
	private boolean[] active;
	private final Good[] nodeGood;
	private final long[] nodeStock;
	private final long[] nodeCapacity;
	private final int[] nodeRegeneration;
	private final long[] prices = { 12L, 18L, 14L, 24L };
	private long initialMoney;
	private long initialGoods;
	private long regenerated;
	private long consumed;
	private long shockLoss;
	private long unmetUpkeep;
	private long externalMoneyIn;
	private long externalMoneyOut;
	/** City money held by nobody: estates of the dead, and the stake a newborn draws. */
	private long treasury;
	private long tick;
	private long randomState;

	public EconomyModel(Config config) {
		this.config = config;
		this.randomState = config.seed();
		int n = config.population();
		this.money = new long[n];
		this.skill = new int[n];
		this.metabolism = new int[n];
		this.aptitude = new int[n];
		this.professions = new Role[n];
		this.goods = new long[n][Good.values().length];
		this.assignedNode = new int[n];
		this.active = new boolean[n];
		Arrays.fill(money, config.startingWealth());
		Arrays.fill(active, true);

		for (int i = 0; i < n; i++) {
			long actorSeed = mix64(config.seed() ^ (i + 1L) * 0x9E3779B97F4A7C15L);
			skill[i] = 80 + (int) Math.floorMod(actorSeed, 41L);
			metabolism[i] = 2 + (int) Math.floorMod(actorSeed >>> 9, 3L);
			aptitude[i] = 80 + (int) Math.floorMod(actorSeed >>> 17, 41L);
			professions[i] = Role.values()[i % Role.values().length];
		}

		// Three nodes per extractive good. The first is a scarce prime node; access
		// to it is deterministic by serial and is the initial spatial divergence.
		this.nodeGood = new Good[] { Good.FOOD, Good.FOOD, Good.FOOD,
				Good.ORE, Good.ORE, Good.ORE, Good.TIMBER, Good.TIMBER, Good.TIMBER };
		this.nodeCapacity = new long[] { 1_800, 700, 350, 1_500, 600, 260, 1_500, 650, 300 };
		this.nodeStock = nodeCapacity.clone();
		this.nodeRegeneration = new int[] { 7, 3, 2, 1, 0, 0, 5, 3, 1 };
		for (int i = 0; i < n; i++) {
			Good needed = productionGood(professions[i]);
			int base = needed == Good.FOOD ? 0 : needed == Good.ORE ? 3 : 6;
			assignedNode[i] = base + (int) Math.floorMod(mix64(config.seed() + i * 31L), 3L);
		}
		this.initialMoney = totalMoney();
		this.initialGoods = totalGoodsIncludingNodes();
	}

	public void advance(long ticks) {
		for (long i = 0; i < ticks; i++) step();
	}

	public void step() {
		tick++;
		regenerateNodes();
		produce();
		clearBuilderInputMarkets();
		clearFoodMarket();
		if (tick % 20 == 0) clearMarket(Good.CRAFTS, Math.max(1, money.length / 10));
		fixedAmountExchange();
		distributeTreasury();
		if (config.shockInterval() > 0 && tick % config.shockInterval() == 0) {
			injectShock(Shock.values()[(int) Math.floorMod(nextLong(), Shock.values().length)]);
		}
		assertConservation();
	}

	private void regenerateNodes() {
		for (int node = 0; node < nodeStock.length; node++) {
			long amount = Math.min(nodeRegeneration[node], nodeCapacity[node] - nodeStock[node]);
			if (amount > 0) {
				nodeStock[node] += amount;
				regenerated += amount;
			}
		}
	}

	private void produce() {
		for (int actor = 0; actor < money.length; actor++) {
			if (!active[actor] || money[actor] < config.liquidityFloor()) continue;
			Role profession = professions[actor];
			if (profession == Role.TRADER) continue;
			if (profession == Role.BUILDER) {
				if (goods[actor][Good.ORE.ordinal()] > 0 && goods[actor][Good.TIMBER.ordinal()] > 0) {
					goods[actor][Good.ORE.ordinal()]--;
					goods[actor][Good.TIMBER.ordinal()]--;
					goods[actor][Good.CRAFTS.ordinal()] += 2;
				}
				continue;
			}
			int node = assignedNode[actor];
			long accessBonus = node % 3 == 0 ? 2 : node % 3 == 1 ? 1 : 0;
			long yield = 1 + accessBonus + (skill[actor] + aptitude[actor] >= 210 ? 1 : 0);
			yield = Math.min(yield, nodeStock[node]);
			if (yield > 0) {
				nodeStock[node] -= yield;
				goods[actor][nodeGood[node].ordinal()] += yield;
			}
		}
	}

	private void clearFoodMarket() {
		int demand = 0;
		long supply = 0;
		for (int actor = 0; actor < money.length; actor++) {
			if (!active[actor]) continue;
			if (tick % (metabolism[actor] * 20L) == 0) demand++;
			supply += goods[actor][Good.FOOD.ordinal()];
		}
		updatePrice(Good.FOOD, demand, supply);
		for (int offset = 0; offset < money.length; offset++) {
			int buyer = (offset + (int) (tick % money.length)) % money.length;
			if (!active[buyer] || tick % (metabolism[buyer] * 20L) != 0) continue;
			if (goods[buyer][Good.FOOD.ordinal()] > 0) {
				goods[buyer][Good.FOOD.ordinal()]--;
				consumed++;
				continue;
			}
			int seller = findSeller(Good.FOOD, buyer);
			long price = prices[Good.FOOD.ordinal()];
			if (seller >= 0 && money[buyer] >= Math.max(config.liquidityFloor(), price)) {
				transfer(buyer, seller, price);
				goods[seller][Good.FOOD.ordinal()]--;
				consumed++;
			} else {
				unmetUpkeep++;
			}
		}
	}

	private void clearBuilderInputMarkets() {
		if (tick % 5 != 0) return;
		long oreSupply = 0;
		long timberSupply = 0;
		int builders = 0;
		for (int actor = 0; actor < money.length; actor++) {
			if (!active[actor]) continue;
			oreSupply += goods[actor][Good.ORE.ordinal()];
			timberSupply += goods[actor][Good.TIMBER.ordinal()];
			if (professions[actor] == Role.BUILDER && money[actor] >= config.liquidityFloor()) builders++;
		}
		updatePrice(Good.ORE, builders, oreSupply);
		updatePrice(Good.TIMBER, builders, timberSupply);
		for (int builder = 0; builder < money.length; builder++) {
			if (!active[builder] || professions[builder] != Role.BUILDER
					|| money[builder] < config.liquidityFloor()) continue;
			if (goods[builder][Good.ORE.ordinal()] == 0) buyInput(builder, Good.ORE);
			if (goods[builder][Good.TIMBER.ordinal()] == 0) buyInput(builder, Good.TIMBER);
		}
	}

	private void buyInput(int buyer, Good good) {
		int seller = findSeller(good, buyer);
		long price = prices[good.ordinal()];
		if (seller >= 0 && money[buyer] >= Math.max(config.liquidityFloor(), price)) {
			transfer(buyer, seller, price);
			goods[seller][good.ordinal()]--;
			goods[buyer][good.ordinal()]++;
		}
	}

	private void clearMarket(Good good, int demand) {
		long supply = 0;
		for (long[] inventory : goods) supply += inventory[good.ordinal()];
		updatePrice(good, demand, supply);
		for (int order = 0; order < demand; order++) {
			int buyer = (int) Math.floorMod(mix64(config.seed() ^ tick * 131L ^ order), money.length);
			if (!active[buyer]) continue;
			int seller = findSeller(good, buyer);
			long price = prices[good.ordinal()];
			if (seller >= 0 && money[buyer] >= Math.max(config.liquidityFloor(), price)) {
				transfer(buyer, seller, price);
				goods[seller][good.ordinal()]--;
				consumed++;
			}
		}
	}

	private int findSeller(Good good, int buyer) {
		int start = (int) Math.floorMod(mix64(config.seed() + tick * 17L + buyer * 97L), money.length);
		for (int offset = 0; offset < money.length; offset++) {
			int seller = (start + offset) % money.length;
			if (seller != buyer && active[seller] && goods[seller][good.ordinal()] > 0) return seller;
		}
		return -1;
	}

	private void updatePrice(Good good, long demand, long supply) {
		long imbalance = demand - supply;
		long scale = Math.max(1L, (demand + supply) / 12L);
		long movement = Math.clamp(imbalance / scale, -3L, 3L);
		prices[good.ordinal()] = Math.clamp(prices[good.ordinal()] + movement, 1L, 200L);
	}

	private void fixedAmountExchange() {
		for (int exchange = 0; exchange < config.exchangesPerTick(); exchange++) {
			int a = nextInt(money.length);
			int b = nextInt(money.length);
			if (a == b || !active[a] || !active[b]
					|| money[a] < config.liquidityFloor() || money[b] < config.liquidityFloor()) continue;
			double chanceA = (skill[a] + aptitude[a])
					/ (double) (skill[a] + aptitude[a] + skill[b] + aptitude[b]);
			int winner = nextUnit() < chanceA ? a : b;
			int loser = winner == a ? b : a;
			if (money[loser] >= config.fixedExchange()) transfer(loser, winner, config.fixedExchange());
		}
	}

	/**
	 * Pays the town purse out to the people. Without this, everything an expedition
	 * mines or loots would pile up in the treasury and never reach a citizen — the
	 * economy would be open on paper and unchanged in practice.
	 *
	 * <p>The reserve held back is one liquidity floor — a small working float, not a
	 * whole starting fortune. Reserving `startingWealth` meant a typical expedition haul
	 * never cleared the bar and nothing ever circulated; a newborn's stake falls back to
	 * a levy on the rich anyway, so the treasury does not need to cover one in full.
	 */
	private void distributeTreasury() {
		long reserve = config.liquidityFloor();
		long payable = treasury - reserve;
		if (payable <= 0) return;
		int living = activeCount();
		if (living == 0) return;
		long share = Math.max(1L, payable / 10L / living);
		long paid = 0;
		for (int actor = 0; actor < money.length && paid + share <= payable; actor++) {
			if (!active[actor]) continue;
			money[actor] = Math.addExact(money[actor], share);
			paid += share;
		}
		treasury -= paid;
	}

	private void transfer(int from, int to, long amount) {
		if (amount < 0 || money[from] < amount) throw new IllegalStateException("invalid money transfer");
		money[from] -= amount;
		money[to] = Math.addExact(money[to], amount);
	}

	public void injectShock(Shock shock) {
		long lost = 0;
		switch (shock) {
			case VEIN_DEPLETION -> {
				int node = 3 + nextInt(3);
				lost += nodeStock[node];
				nodeStock[node] = 0;
				nodeCapacity[node] = 0;
				for (int actor = 0; actor < money.length; actor++) {
					if (professions[actor] == Role.MINER && assignedNode[actor] == node) {
						payRecoveryContract(actor, Role.BUILDER, 220);
					}
				}
			}
			case BLIGHT -> {
				for (int node = 0; node < 3; node++) {
					long amount = nodeStock[node] * config.shockPermille() / 1_000L;
					nodeStock[node] -= amount;
					nodeCapacity[node] -= nodeCapacity[node] * config.shockPermille() / 1_000L;
					lost += amount;
				}
				lost += destroyActorGoods(Good.FOOD, config.shockPermille());
				for (int actor = 0; actor < money.length; actor++) {
					if (professions[actor] == Role.FARMER) payRecoveryContract(actor, Role.LABORER, 140);
				}
			}
			case RAID -> lost += destroyRichestInventories(config.shockPermille());
			case FIRE -> lost += destroyActorGoods(Good.CRAFTS, Math.min(900, config.shockPermille() * 2));
		}
		shockLoss += lost;
	}

	private void payRecoveryContract(int payer, Role recipientRole, long maximum) {
		if (money[payer] <= config.liquidityFloor()) return;
		int start = (int) Math.floorMod(mix64(config.seed() ^ tick ^ payer * 41L), money.length);
		for (int offset = 0; offset < money.length; offset++) {
			int recipient = (start + offset) % money.length;
			if (recipient != payer && active[recipient] && professions[recipient] == recipientRole) {
				long amount = Math.min(maximum, Math.max(0, (money[payer] - config.liquidityFloor()) / 3));
				if (amount > 0) transfer(payer, recipient, amount);
				return;
			}
		}
	}

	private long destroyActorGoods(Good good, int permille) {
		long lost = 0;
		for (long[] inventory : goods) {
			long amount = inventory[good.ordinal()] * permille / 1_000L;
			inventory[good.ordinal()] -= amount;
			lost += amount;
		}
		return lost;
	}

	private long destroyRichestInventories(int permille) {
		Integer[] order = new Integer[money.length];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, Comparator.comparingLong((Integer i) -> netWorth(i)).reversed());
		long lost = 0;
		for (int rank = 0; rank < Math.max(1, order.length / 10); rank++) {
			int actor = order[rank];
			for (Good good : Good.values()) {
				long amount = goods[actor][good.ordinal()] * permille / 1_000L;
				goods[actor][good.ordinal()] -= amount;
				lost += amount;
			}
		}
		return lost;
	}

	public Distribution distribution() {
		// Inert slots left by the dead are not people; counting their zero wealth would
		// drag every quantile and the Gini coefficient toward a poverty that nobody lives.
		long[] wealth = new long[activeCount()];
		int w = 0;
		for (int actor = 0; actor < money.length; actor++) {
			if (active[actor]) wealth[w++] = netWorth(actor);
		}
		if (wealth.length == 0) wealth = new long[] { 0L };
		Arrays.sort(wealth);
		long total = Arrays.stream(wealth).sum();
		long weighted = 0;
		for (int i = 0; i < wealth.length; i++) weighted = Math.addExact(weighted, (i + 1L) * wealth[i]);
		double gini = total == 0 ? 0 : 2.0 * weighted / (wealth.length * (double) total)
				- (wealth.length + 1.0) / wealth.length;
		int poor = 0;
		for (long liquid : money) if (liquid < config.liquidityFloor()) poor++;
		int topStart = Math.max(0, wealth.length - Math.max(1, wealth.length / 20));
		long top = 0;
		for (int i = topStart; i < wealth.length; i++) top += wealth[i];
		return new Distribution(tick, gini, poor / (double) wealth.length, top / (double) total,
				wealth[0], wealth[wealth.length / 4], wealth[wealth.length / 2], wealth[wealth.length * 3 / 4],
				wealth[wealth.length * 9 / 10], wealth[wealth.length - 1], totalMoney(), totalActorGoods(), unmetUpkeep);
	}

	public Conservation conservation() {
		return new Conservation(initialMoney, totalMoney(), initialGoods, regenerated, consumed, shockLoss,
				totalGoodsIncludingNodes(), externalMoneyIn, externalMoneyOut, treasury);
	}

	// ---------- population lifecycle ----------

	public int activeCount() {
		int n = 0;
		for (boolean live : active) {
			if (live) n++;
		}
		return n;
	}

	public boolean isActive(int actor) {
		return actor >= 0 && actor < active.length && active[actor];
	}

	public long treasury() {
		return treasury;
	}

	/**
	 * Ensures a slot exists for {@code index} and brings it to life. A newborn draws a
	 * stake so it starts above the liquidity floor — otherwise it could never trade or
	 * produce, and would be born permanently destitute. The stake comes from the
	 * treasury first (the estates of the dead) and is levied from the richest living
	 * actors only for the shortfall, so no money is created here.
	 */
	public void bringActorToLife(int index, long stake) {
		if (index < 0) throw new IllegalArgumentException("negative actor index");
		if (index >= money.length) grow(index + 1);
		if (active[index]) return;
		long actorSeed = mix64(config.seed() ^ (index + 1L) * 0x9E3779B97F4A7C15L);
		skill[index] = 80 + (int) Math.floorMod(actorSeed, 41L);
		metabolism[index] = 2 + (int) Math.floorMod(actorSeed >>> 9, 3L);
		aptitude[index] = 80 + (int) Math.floorMod(actorSeed >>> 17, 41L);
		professions[index] = Role.values()[index % Role.values().length];
		Good needed = productionGood(professions[index]);
		int base = needed == Good.FOOD ? 0 : needed == Good.ORE ? 3 : 6;
		assignedNode[index] = base + (int) Math.floorMod(mix64(config.seed() + index * 31L), 3L);
		Arrays.fill(goods[index], 0L);
		money[index] = 0L;
		active[index] = true;
		long wanted = Math.max(0L, stake);
		long fromTreasury = Math.min(wanted, treasury);
		treasury -= fromTreasury;
		money[index] = fromTreasury;
		long shortfall = wanted - fromTreasury;
		if (shortfall > 0) levyInto(index, shortfall);
		assertConservation();
	}

	/** Retires a slot: the estate goes to the treasury, the goods are consumed. */
	public void retireActor(int index) {
		if (index < 0 || index >= money.length || !active[index]) return;
		treasury = Math.addExact(treasury, money[index]);
		money[index] = 0L;
		for (Good good : Good.values()) {
			consumed += goods[index][good.ordinal()];
			goods[index][good.ordinal()] = 0L;
		}
		active[index] = false;
		assertConservation();
	}

	/** Takes a shortfall from the richest living actors, above the liquidity floor. */
	private void levyInto(int beneficiary, long amount) {
		Integer[] order = new Integer[money.length];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, Comparator.comparingLong((Integer i) -> money[i]).reversed());
		long remaining = amount;
		for (int rank = 0; rank < order.length && remaining > 0; rank++) {
			int donor = order[rank];
			if (donor == beneficiary || !active[donor]) continue;
			long spare = money[donor] - config.liquidityFloor();
			if (spare <= 0) break; // sorted by wealth: nobody after this one can spare more
			long taken = Math.min(spare, remaining);
			money[donor] -= taken;
			money[beneficiary] = Math.addExact(money[beneficiary], taken);
			remaining -= taken;
		}
	}

	private void grow(int size) {
		int old = money.length;
		money = Arrays.copyOf(money, size);
		skill = Arrays.copyOf(skill, size);
		metabolism = Arrays.copyOf(metabolism, size);
		aptitude = Arrays.copyOf(aptitude, size);
		professions = Arrays.copyOf(professions, size);
		assignedNode = Arrays.copyOf(assignedNode, size);
		active = Arrays.copyOf(active, size);
		goods = Arrays.copyOf(goods, size);
		for (int i = old; i < size; i++) {
			goods[i] = new long[Good.values().length];
			professions[i] = Role.LABORER;
			metabolism[i] = 2;
		}
	}

	// ---------- the open economy ----------

	/**
	 * Wealth carried in from outside the city — an expedition that mined emeralds, or
	 * loot taken off another settlement. This is the seam that makes the economy stop
	 * being zero-sum, and it is booked so conservation still closes.
	 */
	public void depositExternal(long amount) {
		if (amount <= 0) return;
		treasury = Math.addExact(treasury, amount);
		externalMoneyIn += amount;
		assertConservation();
	}

	/** Wealth leaving the city — loot taken FROM it, or an expedition's outfitting cost. */
	public long withdrawExternal(long amount) {
		if (amount <= 0) return 0L;
		long taken = Math.min(amount, treasury);
		treasury -= taken;
		long shortfall = amount - taken;
		if (shortfall > 0) {
			// Raiders do not stop at the town strongbox; they take it off people.
			Integer[] order = new Integer[money.length];
			for (int i = 0; i < order.length; i++) order[i] = i;
			Arrays.sort(order, Comparator.comparingLong((Integer i) -> money[i]).reversed());
			for (int rank = 0; rank < order.length && shortfall > 0; rank++) {
				int victim = order[rank];
				if (!active[victim]) continue;
				long grabbed = Math.min(money[victim], shortfall);
				money[victim] -= grabbed;
				shortfall -= grabbed;
				taken += grabbed;
			}
		}
		externalMoneyOut += taken;
		assertConservation();
		return taken;
	}

	/** Goods carried in from outside — foraged, mined, or looted. */
	public void depositGoods(Good good, long quantity) {
		if (quantity <= 0) return;
		int target = -1;
		for (int actor = 0; actor < money.length; actor++) {
			if (active[actor] && (target < 0 || goods[actor][good.ordinal()] < goods[target][good.ordinal()])) {
				target = actor;
			}
		}
		if (target < 0) return;
		goods[target][good.ordinal()] = Math.addExact(goods[target][good.ordinal()], quantity);
		regenerated += quantity;
		assertConservation();
	}

	/** Goods carried out — looted from this city. Returns what was actually taken. */
	public long removeGoods(Good good, long quantity) {
		long remaining = quantity;
		long taken = 0;
		for (int actor = 0; actor < money.length && remaining > 0; actor++) {
			if (!active[actor]) continue;
			long grabbed = Math.min(goods[actor][good.ordinal()], remaining);
			goods[actor][good.ordinal()] -= grabbed;
			remaining -= grabbed;
			taken += grabbed;
		}
		consumed += taken;
		assertConservation();
		return taken;
	}

	/** Total food the city is holding — what population growth is rationed against. */
	public long foodHeld() {
		long total = 0;
		for (int actor = 0; actor < money.length; actor++) {
			if (active[actor]) total += goods[actor][Good.FOOD.ordinal()];
		}
		return total;
	}

	// ---------- player trade ----------

	/**
	 * A player buys {@code quantity} of {@code good} from {@code actor} for {@code payment}
	 * money. The goods leave the closed system (booked as consumption) and the money
	 * enters it. Returns false — changing nothing — if the actor is not holding that much.
	 */
	public boolean playerBuy(int actor, Good good, long quantity, long payment) {
		if (actor < 0 || actor >= money.length || payment < 0 || quantity < 1) return false;
		if (goods[actor][good.ordinal()] < quantity) return false;
		goods[actor][good.ordinal()] -= quantity;
		consumed += quantity;
		money[actor] = Math.addExact(money[actor], payment);
		externalMoneyIn += payment;
		assertConservation();
		return true;
	}

	/**
	 * A player sells {@code quantity} of {@code good} to {@code actor} for {@code payment}
	 * money. The goods enter the system and the money leaves it. Returns false — changing
	 * nothing — if the actor cannot cover the payment.
	 */
	public boolean playerSell(int actor, Good good, long quantity, long payment) {
		if (actor < 0 || actor >= money.length || payment < 0 || quantity < 1) return false;
		if (money[actor] < payment) return false;
		money[actor] -= payment;
		externalMoneyOut += payment;
		goods[actor][good.ordinal()] = Math.addExact(goods[actor][good.ordinal()], quantity);
		regenerated += quantity;
		assertConservation();
		return true;
	}

	/** Books emeralds paid for stock already removed from an embodied citizen's inventory. */
	public boolean receivePlayerPayment(int actor, long payment) {
		if (actor < 0 || actor >= money.length || !active[actor] || payment < 1) return false;
		money[actor] = Math.addExact(money[actor], payment);
		externalMoneyIn = Math.addExact(externalMoneyIn, payment);
		assertConservation();
		return true;
	}

	/** How much of {@code good} this actor is holding — what it can actually sell a player. */
	public long actorStock(int actor, Good good) {
		return actor < 0 || actor >= money.length ? 0L : goods[actor][good.ordinal()];
	}

	public long[] moneySnapshot() { return money.clone(); }
	public int population() { return money.length; }
	public long actorMoney(int actor) { return money[actor]; }
	public Map<Good, Long> actorGoods(int actor) {
		Map<Good, Long> result = new EnumMap<>(Good.class);
		for (Good good : Good.values()) result.put(good, goods[actor][good.ordinal()]);
		return Map.copyOf(result);
	}
	public void setActorGoods(int actor, Map<Good, Long> inventory) {
		for (Good good : Good.values()) {
			long next = Math.max(0, inventory.getOrDefault(good, 0L));
			long delta = next - goods[actor][good.ordinal()];
			if (delta > 0) regenerated += delta; // embodied world production
			if (delta < 0) consumed -= delta; // embodied consumption/loss
			goods[actor][good.ordinal()] = next;
		}
	}
	public long[] netWorthSnapshot() {
		long[] result = new long[money.length];
		for (int i = 0; i < result.length; i++) result[i] = netWorth(i);
		return result;
	}
	public long tick() { return tick; }
	public long price(Good good) { return prices[good.ordinal()]; }

	private long netWorth(int actor) {
		long value = money[actor];
		for (Good good : Good.values()) value += goods[actor][good.ordinal()] * prices[good.ordinal()];
		return value;
	}

	private long totalMoney() { return Arrays.stream(money).sum(); }
	private long totalActorGoods() {
		long total = 0;
		for (long[] inventory : goods) for (long amount : inventory) total += amount;
		return total;
	}
	private long totalGoodsIncludingNodes() { return totalActorGoods() + Arrays.stream(nodeStock).sum(); }

	private void assertConservation() {
		Conservation audit = conservation();
		if (!audit.balanced()) throw new IllegalStateException("economic conservation failed: " + audit);
	}

	private Good productionGood(Role profession) {
		return switch (profession) {
			case FARMER -> Good.FOOD;
			case MINER -> Good.ORE;
			default -> Good.TIMBER;
		};
	}

	private int nextInt(int bound) { return (int) Math.floorMod(nextLong(), bound); }
	private double nextUnit() { return (nextLong() >>> 11) * 0x1.0p-53; }
	private long nextLong() {
		randomState += 0x9E3779B97F4A7C15L;
		return mix64(randomState);
	}
	private static long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	public String encode() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				out.writeInt(0x57464534); // WFE4 — adds the treasury, the active mask, and growth
				out.writeInt(money.length); out.writeLong(config.seed()); out.writeLong(config.startingWealth());
				out.writeLong(config.liquidityFloor()); out.writeLong(config.fixedExchange());
				out.writeInt(config.exchangesPerTick()); out.writeInt(config.shockInterval());
				out.writeInt(config.shockPermille());
				out.writeLong(tick); out.writeLong(randomState); out.writeLong(regenerated); out.writeLong(consumed);
				out.writeLong(shockLoss); out.writeLong(unmetUpkeep);
				out.writeLong(externalMoneyIn); out.writeLong(externalMoneyOut); out.writeLong(treasury);
				out.writeLong(initialMoney); out.writeLong(initialGoods);
				for (int actor = 0; actor < money.length; actor++) {
					out.writeLong(money[actor]); out.writeInt(skill[actor]); out.writeInt(metabolism[actor]);
					out.writeInt(aptitude[actor]); out.writeByte(professions[actor].ordinal());
					out.writeInt(assignedNode[actor]); out.writeBoolean(active[actor]);
					for (Good good : Good.values()) out.writeLong(goods[actor][good.ordinal()]);
				}
				out.writeInt(nodeStock.length);
				for (int node = 0; node < nodeStock.length; node++) {
					out.writeLong(nodeStock[node]); out.writeLong(nodeCapacity[node]); out.writeInt(nodeRegeneration[node]);
				}
				for (long price : prices) out.writeLong(price);
			}
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (IOException impossible) {
			throw new IllegalStateException("could not encode economy", impossible);
		}
	}

	public static EconomyModel decode(String encoded) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
			// WFE2 predates the player-trade ledger; its two external totals are zero.
			int version = in.readInt();
			if (version != 0x57464532 && version != 0x57464533 && version != 0x57464534) {
				throw new IllegalArgumentException("unknown economy snapshot version");
			}
			boolean hasExternalLedger = version >= 0x57464533;
			boolean hasTreasury = version >= 0x57464534;
			Config config = new Config(in.readInt(), in.readLong(), in.readLong(), in.readLong(), in.readLong(),
					in.readInt(), in.readInt(), in.readInt());
			EconomyModel model = new EconomyModel(config);
			model.tick = in.readLong(); model.randomState = in.readLong(); model.regenerated = in.readLong();
			model.consumed = in.readLong(); model.shockLoss = in.readLong(); model.unmetUpkeep = in.readLong();
			if (hasExternalLedger) {
				model.externalMoneyIn = in.readLong();
				model.externalMoneyOut = in.readLong();
			}
			// Snapshots written before growth existed have every slot alive and an empty
			// treasury, which is exactly what a fixed-population city was.
			if (hasTreasury) {
				model.treasury = in.readLong();
				// Re-deriving these from the constructor would use the GROWN population and
				// break conservation the first time a city that had children was reloaded.
				model.initialMoney = in.readLong();
				model.initialGoods = in.readLong();
			}
			Arrays.fill(model.active, true);
			for (int actor = 0; actor < model.money.length; actor++) {
				model.money[actor] = in.readLong(); model.skill[actor] = in.readInt();
				model.metabolism[actor] = in.readInt(); model.aptitude[actor] = in.readInt();
				model.professions[actor] = Role.values()[in.readUnsignedByte()]; model.assignedNode[actor] = in.readInt();
				if (hasTreasury) model.active[actor] = in.readBoolean();
				for (Good good : Good.values()) model.goods[actor][good.ordinal()] = in.readLong();
			}
			int nodes = in.readInt();
			if (nodes != model.nodeStock.length) throw new IllegalArgumentException("economy node count changed");
			for (int node = 0; node < nodes; node++) {
				model.nodeStock[node] = in.readLong(); model.nodeCapacity[node] = in.readLong();
				model.nodeRegeneration[node] = in.readInt();
			}
			for (int good = 0; good < model.prices.length; good++) model.prices[good] = in.readLong();
			model.assertConservation();
			return model;
		} catch (IOException | IllegalArgumentException exception) {
			throw new IllegalArgumentException("invalid economy snapshot", exception);
		}
	}

	private EconomyModel() { throw new UnsupportedOperationException(); }
}
