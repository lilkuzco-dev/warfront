package io.github.lilkuzcodev.warfront.entity;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.data.Doctrine;
import io.github.lilkuzcodev.warfront.data.Faction;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.ai.PatrolGoal;
import io.github.lilkuzcodev.warfront.entity.ai.RetreatGoal;
import io.github.lilkuzcodev.warfront.entity.ai.StationGoal;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The one soldier entity type. Faction, rank, squad, home base and station are DATA on
 * the entity; every behavioral difference between factions comes from doctrine weights
 * read at decision time (architecture note 1 — no faction logic is hardcoded here).
 */
public class SoldierEntity extends PathfinderMob {
	private static final EntityDataAccessor<String> FACTION =
			SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.STRING);

	public static final Doctrine DEFAULT_DOCTRINE =
			new Doctrine(0.5F, 0.5F, 4, 1, 0.0F, 0.0F, 0.5F, 1.0F, 0, 1.0F, 0.0F);

	private String rank = "soldier";
	private @Nullable UUID squadId;
	private @Nullable BlockPos homePos;
	private @Nullable BlockPos stationPos;
	private long scatterUntil; // Sarab scatter timer (absolute game time)
	private String baseKey = ""; // garrison membership (BaseManager ledger key)
	private boolean roaming; // inter-base roaming squad member
	private @Nullable BlockPos travelFrom;
	private @Nullable BlockPos travelTo;
	private long lastLoadedGameTime; // roaming despawn bookkeeping

	public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 24.0)
				.add(Attributes.MOVEMENT_SPEED, 0.32)
				.add(Attributes.ATTACK_DAMAGE, 3.0)
				.add(Attributes.FOLLOW_RANGE, 32.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(FACTION, "");
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(2, new RetreatGoal(this));
		this.goalSelector.addGoal(3, new StationGoal(this));
		this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.15, true));
		this.goalSelector.addGoal(5, new io.github.lilkuzcodev.warfront.entity.ai.TravelGoal(this));
		this.goalSelector.addGoal(6, new PatrolGoal(this));
		this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.6) {
			@Override
			public boolean canUse() {
				// ambush doctrine holds position instead of wandering; stationed soldiers stay put
				return SoldierEntity.this.stationPos == null
						&& SoldierEntity.this.doctrine().ambushBias() < 0.5F && super.canUse();
			}
		});
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

		this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, SoldierEntity.class, true,
				(target, level) -> target instanceof SoldierEntity other && this.isHostileToSoldier(other)));
		this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Player.class, true,
				(target, level) -> target instanceof ServerPlayer player && this.isHostileToPlayer(player, level)));
	}

	// ---------- doctrine-driven checks ----------
	public Doctrine doctrine() {
		Faction faction = WarfrontRegistry.faction(getFaction());
		return faction == null ? DEFAULT_DOCTRINE : faction.doctrine();
	}

	private boolean isHostileToSoldier(SoldierEntity other) {
		if (isScattered() || getFaction().isEmpty() || other.getFaction().isEmpty()) {
			return false;
		}
		if (!"hostile".equals(WarfrontRegistry.relation(getFaction(), other.getFaction()))) {
			return false;
		}
		return engagementAllowed(other);
	}

	private boolean isHostileToPlayer(ServerPlayer player, ServerLevel level) {
		if (isScattered() || getFaction().isEmpty() || player.isCreative() || player.isSpectator()) {
			return false;
		}
		return WarfrontState.get(level.getServer()).isHostileTo(player.getUUID(), getFaction())
				&& engagementAllowed(player);
	}

	/**
	 * Night-preference gate: high night-bias factions only initiate distant engagements
	 * at night; by day they strike only at close range (or when already hurt).
	 */
	private boolean engagementAllowed(LivingEntity target) {
		Doctrine doctrine = doctrine();
		long dayTime = level().getOverworldClockTime() % 24000L;
		boolean night = dayTime >= 13000L && dayTime <= 23000L;
		if (doctrine.nightBias() < 0.5F || night || getLastHurtByMob() == target) {
			return true;
		}
		return distanceTo(target) < 8.0;
	}

	// ---------- data accessors ----------
	public String getFaction() {
		return this.entityData.get(FACTION);
	}

	public void setFaction(String faction) {
		this.entityData.set(FACTION, faction);
	}

	public String getRank() {
		return rank;
	}

	public void setRank(String value) {
		this.rank = value;
	}

	public @Nullable UUID getSquadId() {
		return squadId;
	}

	public void setSquadId(@Nullable UUID id) {
		this.squadId = id;
	}

	public @Nullable BlockPos getHomePos() {
		return homePos;
	}

	public void setHomePos(@Nullable BlockPos pos) {
		this.homePos = pos;
	}

	public @Nullable BlockPos getStationPos() {
		return stationPos;
	}

	public void setStationPos(@Nullable BlockPos pos) {
		this.stationPos = pos;
	}

	public String getBaseKey() {
		return baseKey;
	}

	public void setBaseKey(String key) {
		this.baseKey = key;
	}

	public boolean isRoaming() {
		return roaming;
	}

	/** Marks this soldier as an inter-base roaming squad member shuttling from→to. */
	public void setRoute(BlockPos from, BlockPos to) {
		this.roaming = true;
		this.travelFrom = from;
		this.travelTo = to;
		this.lastLoadedGameTime = level().getGameTime();
	}

	public @Nullable BlockPos getTravelTo() {
		return travelTo;
	}

	/** Route leg complete: turn around and shuttle back. */
	public void swapRoute() {
		BlockPos from = travelFrom;
		this.travelFrom = travelTo;
		this.travelTo = from;
	}

	public boolean isScattered() {
		return level().getGameTime() < scatterUntil;
	}

	public void scatter(int ticks) {
		this.scatterUntil = level().getGameTime() + ticks;
		this.setTarget(null);
	}

	public int techLevel() {
		if (!(level() instanceof ServerLevel serverLevel) || getFaction().isEmpty()) {
			return 0;
		}
		return WarfrontState.get(serverLevel.getServer()).techLevel(getFaction());
	}

	public boolean stationsUnlocked() {
		return WarfrontRegistry.tech().unlocked(techLevel(), "stations");
	}

	// ---------- gear ----------
	/** Equips armor + weapon for the given tech level (+doctrine gear bonus), leather dyed in faction colors. */
	public void applyLoadout(int techLevel) {
		Faction faction = WarfrontRegistry.faction(getFaction());
		int tier = Math.clamp(techLevel + doctrine().gearBonus(), 0, 4);
		String gear = WarfrontRegistry.tech().gearByLevel().getOrDefault(tier, "leather");
		ItemStack head;
		ItemStack chest;
		ItemStack legs;
		ItemStack feet;
		ItemStack weapon;
		switch (gear) {
			case "chainmail" -> {
				head = new ItemStack(Items.LEATHER_HELMET);
				chest = new ItemStack(Items.CHAINMAIL_CHESTPLATE);
				legs = new ItemStack(Items.LEATHER_LEGGINGS);
				feet = new ItemStack(Items.LEATHER_BOOTS);
				weapon = new ItemStack(Items.STONE_SWORD);
			}
			case "iron" -> {
				head = new ItemStack(Items.IRON_HELMET);
				chest = new ItemStack(Items.IRON_CHESTPLATE);
				legs = new ItemStack(Items.LEATHER_LEGGINGS);
				feet = new ItemStack(Items.IRON_BOOTS);
				weapon = new ItemStack(Items.IRON_SWORD);
			}
			case "diamond" -> {
				head = new ItemStack(Items.DIAMOND_HELMET);
				chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
				legs = new ItemStack(Items.DIAMOND_LEGGINGS);
				feet = new ItemStack(Items.DIAMOND_BOOTS);
				weapon = new ItemStack(Items.DIAMOND_SWORD);
			}
			case "netherite" -> {
				head = new ItemStack(Items.NETHERITE_HELMET);
				chest = new ItemStack(Items.NETHERITE_CHESTPLATE);
				legs = new ItemStack(Items.NETHERITE_LEGGINGS);
				feet = new ItemStack(Items.NETHERITE_BOOTS);
				weapon = new ItemStack(Items.NETHERITE_SWORD);
			}
			default -> { // leather militia
				head = new ItemStack(Items.LEATHER_HELMET);
				chest = new ItemStack(Items.LEATHER_CHESTPLATE);
				legs = new ItemStack(Items.LEATHER_LEGGINGS);
				feet = new ItemStack(Items.LEATHER_BOOTS);
				weapon = new ItemStack(Items.WOODEN_SWORD);
			}
		}
		if (faction != null) {
			for (ItemStack stack : new ItemStack[] { head, chest, legs, feet }) {
				if (stack.is(Items.LEATHER_HELMET) || stack.is(Items.LEATHER_CHESTPLATE)
						|| stack.is(Items.LEATHER_LEGGINGS) || stack.is(Items.LEATHER_BOOTS)) {
					stack.set(DataComponents.DYED_COLOR, new DyedItemColor(faction.primaryColor()));
				}
			}
		}
		setItemSlot(EquipmentSlot.HEAD, head);
		setItemSlot(EquipmentSlot.CHEST, chest);
		setItemSlot(EquipmentSlot.LEGS, legs);
		setItemSlot(EquipmentSlot.FEET, feet);
		setItemSlot(EquipmentSlot.MAINHAND, weapon);
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			setDropChance(slot, 0.0F);
		}
	}

	// ---------- persistence ----------
	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("warfront_faction", getFaction());
		output.putString("warfront_rank", rank);
		output.putString("warfront_squad", squadId == null ? "" : squadId.toString());
		output.storeNullable("warfront_home", BlockPos.CODEC, homePos);
		output.putString("warfront_base", baseKey);
		output.putBoolean("warfront_roaming", roaming);
		output.storeNullable("warfront_travel_from", BlockPos.CODEC, travelFrom);
		output.storeNullable("warfront_travel_to", BlockPos.CODEC, travelTo);
		output.putLong("warfront_last_loaded", lastLoadedGameTime);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		setFaction(input.getStringOr("warfront_faction", ""));
		rank = input.getStringOr("warfront_rank", "soldier");
		String squad = input.getStringOr("warfront_squad", "");
		squadId = squad.isEmpty() ? null : UUID.fromString(squad);
		homePos = input.read("warfront_home", BlockPos.CODEC).orElse(null);
		baseKey = input.getStringOr("warfront_base", "");
		roaming = input.getBooleanOr("warfront_roaming", false);
		travelFrom = input.read("warfront_travel_from", BlockPos.CODEC).orElse(null);
		travelTo = input.read("warfront_travel_to", BlockPos.CODEC).orElse(null);
		lastLoadedGameTime = input.getLongOr("warfront_last_loaded", 0L);
		// Structure-template soldiers ship with faction data but empty hands:
		// outfit them for the faction's current tech level on first load.
		if (!getFaction().isEmpty() && getMainHandItem().isEmpty()) {
			applyLoadout(techLevel());
			setPersistenceRequired();
		}
	}

	private boolean squadChecked;

	@Override
	public void tick() {
		super.tick();
		if (!level().isClientSide()) {
			if (!squadChecked) {
				squadChecked = true;
				SquadManager.ensureRegistered(this);
				// structure-template garrisons anchor their patrol home where they spawned
				if (homePos == null && !getFaction().isEmpty()) {
					homePos = blockPosition();
				}
				// roaming squads that sat unloaded past their JSON deadline despawn on re-load
				if (roaming && lastLoadedGameTime > 0
						&& level().getGameTime() - lastLoadedGameTime > roamDespawnTicks()) {
					discard();
					return;
				}
				io.github.lilkuzcodev.warfront.systems.BaseManager.tryAdopt(this);
			}
			if (roaming && tickCount % 200 == 0) {
				lastLoadedGameTime = level().getGameTime();
			}
		}
	}

	private long roamDespawnTicks() {
		Faction faction = WarfrontRegistry.faction(getFaction());
		return faction == null ? 12000L : faction.population().roamDespawnTicks();
	}

	@Override
	public void die(net.minecraft.world.damagesource.DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel serverLevel) {
			SquadManager.onSoldierDeath(this);
			StationManager.release(this);
			if (!baseKey.isEmpty()) {
				io.github.lilkuzcodev.warfront.systems.BaseManager.onSoldierDeath(serverLevel, baseKey);
			}
			if (source.getEntity() instanceof ServerPlayer player && !getFaction().isEmpty()) {
				WarfrontState state = WarfrontState.get(serverLevel.getServer());
				long clock = WarfrontState.clock(serverLevel);
				state.recordEvent(player.getUUID(), getFaction(), "killed_soldier", clock);
				// the killing blow doesn't fire AFTER_DAMAGE, so the standing penalty
				// for the kill itself lands here (one-shot kills must not be free)
				state.addStanding(player.getUUID(), getFaction(), WarfrontRegistry.standing().attackPenalty());
				io.github.lilkuzcodev.warfront.dialogue.WorkOrders.onSoldierKilled(player, this);
				// combat aid: factions hostile to the victim with soldiers watching remember the favor
				java.util.Set<String> witnesses = new java.util.HashSet<>();
				for (SoldierEntity witness : serverLevel.getEntitiesOfClass(SoldierEntity.class,
						getBoundingBox().inflate(24), w -> w != this && !w.getFaction().isEmpty()
								&& "hostile".equals(WarfrontRegistry.relation(w.getFaction(), getFaction())))) {
					witnesses.add(witness.getFaction());
				}
				for (String faction : witnesses) {
					state.recordEvent(player.getUUID(), faction, "aided_in_combat", clock);
				}
			}
			io.github.lilkuzcodev.warfront.dialogue.DialogueSessions.onSoldierGone(this);
		}
	}

	public static net.minecraft.resources.Identifier textureFor(String faction) {
		return Warfront.id("textures/entity/soldier/" + (faction.isEmpty() ? "vostok" : faction) + ".png");
	}

	// ---------- dialogue (Stage 4) ----------
	@Override
	protected net.minecraft.world.InteractionResult mobInteract(Player player,
			net.minecraft.world.InteractionHand hand) {
		if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || getFaction().isEmpty()) {
			return super.mobInteract(player, hand);
		}
		if (level().isClientSide()) {
			return net.minecraft.world.InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer serverPlayer) || getTarget() != null || isScattered()) {
			return net.minecraft.world.InteractionResult.PASS;
		}
		// hostile-standing players get steel, not words (targeting AI handles them)
		if (WarfrontState.get(serverPlayer.level().getServer()).isHostileTo(serverPlayer.getUUID(), getFaction())) {
			return net.minecraft.world.InteractionResult.PASS;
		}
		io.github.lilkuzcodev.warfront.dialogue.DialogueSessions.open(serverPlayer, this);
		return net.minecraft.world.InteractionResult.CONSUME;
	}

	// ---------- quartermaster trading (Merchant, offers built per player) ----------
	private @Nullable Player tradingPlayer;
	private net.minecraft.world.item.trading.MerchantOffers offers = new net.minecraft.world.item.trading.MerchantOffers();

	/** Opens standing+disposition-priced trades. Only quartermasters sell. */
	public void openQuartermaster(ServerPlayer player) {
		if (!"quartermaster".equals(rank) || !(level() instanceof ServerLevel serverLevel)) {
			return;
		}
		var stock = io.github.lilkuzcodev.warfront.dialogue.DialogueRegistry.quartermaster(getFaction());
		if (stock == null) {
			return;
		}
		WarfrontState state = WarfrontState.get(serverLevel.getServer());
		String standingLabel = WarfrontRegistry.standing().label(state.standing(player.getUUID(), getFaction()));
		String bandGroup = io.github.lilkuzcodev.warfront.data.DispositionConfig.bandGroup(
				state.dispositionBand(player.getUUID(), getFaction(), WarfrontState.clock(serverLevel)));
		int standingRank = standingRank(standingLabel);
		float multiplier = stock.standingMultiplier().getOrDefault(standingLabel, 1.0F)
				* stock.dispositionMultiplier().getOrDefault(bandGroup, 1.0F);
		offers = new net.minecraft.world.item.trading.MerchantOffers();
		for (var offer : stock.offers()) {
			if (standingRank < standingRank(offer.minStanding())) {
				continue;
			}
			var costItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getValue(net.minecraft.resources.Identifier.parse(offer.costItem()));
			var resultItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getValue(net.minecraft.resources.Identifier.parse(offer.resultItem()));
			int cost = Math.max(1, Math.round(offer.costCount() * multiplier));
			offers.add(new net.minecraft.world.item.trading.MerchantOffer(
					new net.minecraft.world.item.trading.ItemCost(costItem, cost),
					new ItemStack(resultItem, offer.resultCount()), 12, 2, 0.0F));
		}
		if (offers.isEmpty()) {
			return;
		}
		this.tradingPlayer = player;
		merchant.openTradingScreen(player, net.minecraft.network.chat.Component.literal(
				io.github.lilkuzcodev.warfront.dialogue.DialogueRegistry.soldierName(getFaction(), getUUID())), 0);
	}

	private static int standingRank(String label) {
		return switch (label) {
			case "trusted" -> 2;
			case "friendly" -> 1;
			default -> 0;
		};
	}

	/** Merchant facade: soldiers are not villagers, so the interface lives on a delegate. */
	public final net.minecraft.world.item.trading.Merchant merchant = new net.minecraft.world.item.trading.Merchant() {
		@Override
		public void setTradingPlayer(@Nullable Player player) {
			tradingPlayer = player;
		}

		@Override
		public Player getTradingPlayer() {
			return tradingPlayer;
		}

		@Override
		public net.minecraft.world.item.trading.MerchantOffers getOffers() {
			return offers;
		}

		@Override
		public void overrideOffers(net.minecraft.world.item.trading.MerchantOffers merchantOffers) {
		}

		@Override
		public void notifyTrade(net.minecraft.world.item.trading.MerchantOffer offer) {
			// trading builds disposition memory
			if (tradingPlayer instanceof ServerPlayer serverPlayer && level() instanceof ServerLevel serverLevel) {
				WarfrontState.get(serverLevel.getServer()).recordEvent(serverPlayer.getUUID(), getFaction(),
						"traded", WarfrontState.clock(serverLevel));
			}
		}

		@Override
		public void notifyTradeUpdated(ItemStack stack) {
		}

		@Override
		public int getVillagerXp() {
			return 0;
		}

		@Override
		public void overrideXp(int xp) {
		}

		@Override
		public boolean showProgressBar() {
			return false;
		}

		@Override
		public net.minecraft.sounds.SoundEvent getNotifyTradeSound() {
			return net.minecraft.sounds.SoundEvents.VILLAGER_YES;
		}

		@Override
		public boolean isClientSide() {
			return level().isClientSide();
		}

		@Override
		public boolean stillValid(Player player) {
			return tradingPlayer == player && isAlive() && player.distanceToSqr(SoldierEntity.this) < 64.0;
		}
	};
}
