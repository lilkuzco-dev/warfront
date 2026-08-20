package io.github.lilkuzcodev.warfront.entity;

import io.github.lilkuzcodev.warfront.civilization.CitizenProfession;
import io.github.lilkuzcodev.warfront.civilization.CivilizationManager;
import io.github.lilkuzcodev.warfront.civilization.CivilizationMath;
import io.github.lilkuzcodev.warfront.civilization.EconomyManager;
import io.github.lilkuzcodev.warfront.civilization.EconomyModel;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.entity.ai.CitizenWorkGoal;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Tier-1 projection of one persistent civilization actor record. */
public final class CitizenEntity extends PathfinderMob {
	private static final EntityDataAccessor<String> PROFESSION =
			SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);

	private String cityId = "";
	private long serial = -1L;
	private BlockPos homePos = BlockPos.ZERO;
	private long workTicks;
	private final Map<String, Integer> inventory = new HashMap<>();
	private final Map<net.minecraft.world.item.trading.MerchantOffer, CitizenTrade> pendingTrades =
			new IdentityHashMap<>();
	private @org.jspecify.annotations.Nullable Player tradingPlayer;
	private net.minecraft.world.item.trading.MerchantOffers offers =
			new net.minecraft.world.item.trading.MerchantOffers();
	private boolean groundSpawnChecked;
	private boolean ladderRemoval;

	public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
		super(type, level);
		setCanPickUpLoot(false);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.30)
				.add(Attributes.FOLLOW_RANGE, 24.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(PROFESSION, CitizenProfession.LABORER.id());
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Monster.class, 14.0F, 1.15, 1.45));
		goalSelector.addGoal(2, new PanicGoal(this, 1.4));
		goalSelector.addGoal(3, new CitizenWorkGoal(this));
		goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.65));
		goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
		goalSelector.addGoal(9, new RandomLookAroundGoal(this));
	}

	public String cityId() { return cityId; }
	public long serial() { return serial; }
	public BlockPos homePos() { return homePos; }
	public CitizenProfession profession() { return CitizenProfession.byId(entityData.get(PROFESSION)); }
	public long workTicks() { return workTicks; }
	public Map<String, Integer> inventorySnapshot() { return Map.copyOf(inventory); }

	/** One-time migration for records created by the former roof-first spawn scan. */
	public void ensureGroundSpawn(ServerLevel level) {
		if (groundSpawnChecked) return;
		groundSpawnChecked = true;
		BlockPos ground = io.github.lilkuzcodev.warfront.systems.SpawnSafety.openGroundNear(
				level, blockPosition().getX(), blockPosition().getZ(), 8);
		if (ground != null && getY() - ground.getY() >= 3.0) {
			setPos(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
		}
	}

	public void initialize(String cityId, long serial, CitizenProfession profession, BlockPos home,
			long workTicks, Map<String, Integer> inventory) {
		this.cityId = cityId;
		this.serial = serial;
		this.homePos = home.immutable();
		this.workTicks = workTicks;
		this.inventory.clear();
		this.inventory.putAll(inventory);
		this.entityData.set(PROFESSION, profession.id());
		setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(profession.tool()));
		setDropChance(EquipmentSlot.MAINHAND, 0.0F);
		setPersistenceRequired();
	}

	/** Completes one real, visible work tick and returns true at the production boundary. */
	public boolean advanceEmbodiedWork() {
		workTicks++;
		if (workTicks < CivilizationMath.WORK_CYCLE_TICKS) return false;
		workTicks = 0;
		return true;
	}

	public void store(String itemId, int count) {
		if (count > 0) inventory.merge(itemId, count, Math::addExact);
	}

	public boolean consume(String itemId, int count) {
		int available = inventory.getOrDefault(itemId, 0);
		if (count < 1 || available < count) return false;
		if (available == count) inventory.remove(itemId);
		else inventory.put(itemId, available - count);
		return true;
	}

	public void removeForLadder() {
		ladderRemoval = true;
		discard();
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (!ladderRemoval && level() instanceof ServerLevel level && serial >= 0 && !cityId.isEmpty()) {
			CivilizationManager.onCitizenDeath(level, cityId, serial);
		}
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND || serial < 0 || cityId.isEmpty()) {
			return super.mobInteract(player, hand);
		}
		if (level().isClientSide()) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer serverPlayer) || !(level() instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}
		buildOffers(serverLevel.getServer());
		tradingPlayer = player;
		merchant.openTradingScreen(serverPlayer, Component.literal(displayProfession() + " #" + serial
				+ " — " + cityId), 0);
		return InteractionResult.CONSUME;
	}

	/** Builds native villager-screen offers from this citizen's live purse and inventory. */
	private void buildOffers(MinecraftServer server) {
		offers = new net.minecraft.world.item.trading.MerchantOffers();
		pendingTrades.clear();
		var city = city(server);
		if (city == null) return;
		int lot = WarfrontRegistry.economy().tradeLot();
		long remainingMoney = EconomyManager.actorMoney(server, cityId, serial);
		for (EconomyModel.Good good : EconomyModel.Good.values()) {
			long stock = EconomyManager.actorStock(server, cityId, serial, good);
			String itemId = EconomyManager.itemOf(good);
			net.minecraft.world.item.Item item = itemOrAir(itemId);
			if (item == Items.AIR) continue;

			long buyPrice = EconomyManager.lotPriceEmeralds(server, city, good, true);
			int buyUses = (int) Math.min(12L, stock / lot);
			if (buyUses > 0 && buyPrice <= 64L) {
				var offer = new net.minecraft.world.item.trading.MerchantOffer(
						new net.minecraft.world.item.trading.ItemCost(Items.EMERALD, (int) buyPrice),
						new ItemStack(item, lot), buyUses, 2, 0.0F);
				offers.add(offer);
				pendingTrades.put(offer, new CitizenTrade(good, true, buyPrice, itemId, lot));
			}

			long sellPrice = EconomyManager.lotPriceEmeralds(server, city, good, false);
			long tradeValue = EconomyManager.moneyOf(sellPrice);
			long affordable = remainingMoney / tradeValue;
			int sellUses = (int) Math.min(12L, affordable);
			if (sellUses > 0 && sellPrice <= 64L) {
				var offer = new net.minecraft.world.item.trading.MerchantOffer(
						new net.minecraft.world.item.trading.ItemCost(item, lot),
						new ItemStack(Items.EMERALD, (int) sellPrice), sellUses, 2, 0.0F);
				offers.add(offer);
				pendingTrades.put(offer, new CitizenTrade(good, false, sellPrice, itemId, lot));
				remainingMoney -= (long) sellUses * tradeValue;
			}
		}
	}

	private String displayProfession() {
		String id = profession().id();
		return Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}

	private record CitizenTrade(EconomyModel.Good good, boolean playerIsBuying,
			long emeralds, String itemId, int lot) {}

	/** Merchant facade keeps the trade authoritative while using Minecraft's villager UI. */
	public final net.minecraft.world.item.trading.Merchant merchant = new net.minecraft.world.item.trading.Merchant() {
		@Override public void setTradingPlayer(@org.jspecify.annotations.Nullable Player player) {
			tradingPlayer = player;
		}
		@Override public Player getTradingPlayer() { return tradingPlayer; }
		@Override public net.minecraft.world.item.trading.MerchantOffers getOffers() { return offers; }
		@Override public void overrideOffers(net.minecraft.world.item.trading.MerchantOffers ignored) {}

		@Override
		public void notifyTrade(net.minecraft.world.item.trading.MerchantOffer offer) {
			CitizenTrade trade = pendingTrades.get(offer);
			if (trade == null || !(level() instanceof ServerLevel serverLevel)) return;
			if (!EconomyManager.trade(serverLevel.getServer(), cityId, serial, trade.good(),
					trade.playerIsBuying(), trade.emeralds())) {
				if (tradingPlayer != null) tradingPlayer.sendSystemMessage(Component.literal("That trade fell through."));
				return;
			}
			// The embodied record feeds its inventory back into the economic model, so
			// mirror the completed screen trade here to prevent stock duplication.
			if (trade.playerIsBuying()) consume(trade.itemId(), trade.lot());
			else store(trade.itemId(), trade.lot());
		}

		@Override public void notifyTradeUpdated(ItemStack stack) {}
		@Override public int getVillagerXp() { return 0; }
		@Override public void overrideXp(int xp) {}
		@Override public boolean showProgressBar() { return false; }
		@Override public net.minecraft.sounds.SoundEvent getNotifyTradeSound() {
			return net.minecraft.sounds.SoundEvents.VILLAGER_YES;
		}
		@Override public boolean isClientSide() { return level().isClientSide(); }
		@Override public boolean stillValid(Player player) {
			return tradingPlayer == player && isAlive() && player.distanceToSqr(CitizenEntity.this) < 64.0;
		}
	};

	private io.github.lilkuzcodev.warfront.civilization.CivilizationState.CityRecord city(MinecraftServer server) {
		return io.github.lilkuzcodev.warfront.civilization.CivilizationState.get(server).city(cityId);
	}

	private static net.minecraft.world.item.Item itemOrAir(String id) {
		return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
	}

	/**
	 * Drops the carried purse and goods. Only the visible float is ever carried, so this
	 * cannot become an emerald farm — the rest of a citizen's wealth is abstract holdings
	 * that die with the record.
	 */
	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
		super.dropCustomDeathLoot(level, source, recentlyHit);
		for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
			net.minecraft.world.item.Item item = itemOrAir(entry.getKey());
			if (item == Items.AIR || entry.getValue() <= 0) continue;
			spawnAtLocation(level, new ItemStack(item, entry.getValue()));
		}
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("warfront_city", cityId);
		output.putLong("warfront_serial", serial);
		output.putString("warfront_profession", profession().id());
		output.store("warfront_home", BlockPos.CODEC, homePos);
		output.putLong("warfront_work_ticks", workTicks);
		output.putString("warfront_inventory", encodeInventory(inventory));
		output.putBoolean("warfront_ground_spawn_checked", groundSpawnChecked);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		cityId = input.getStringOr("warfront_city", "");
		serial = input.getLongOr("warfront_serial", -1L);
		CitizenProfession profession = CitizenProfession.byId(input.getStringOr("warfront_profession", "laborer"));
		entityData.set(PROFESSION, profession.id());
		homePos = input.read("warfront_home", BlockPos.CODEC).orElse(blockPosition());
		workTicks = input.getLongOr("warfront_work_ticks", 0L);
		inventory.clear();
		decodeInventory(input.getStringOr("warfront_inventory", ""), inventory);
		groundSpawnChecked = input.getBooleanOr("warfront_ground_spawn_checked", false);
		setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(profession.tool()));
		setDropChance(EquipmentSlot.MAINHAND, 0.0F);
	}

	private static String encodeInventory(Map<String, Integer> inventory) {
		return inventory.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(";"));
	}

	private static void decodeInventory(String encoded, Map<String, Integer> output) {
		for (String part : encoded.split(";")) {
			int split = part.lastIndexOf('=');
			if (split <= 0) continue;
			try {
				int count = Integer.parseInt(part.substring(split + 1));
				if (count > 0) output.put(part.substring(0, split), count);
			} catch (NumberFormatException ignored) {}
		}
	}
}
