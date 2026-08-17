package io.github.lilkuzcodev.warfront.entity;

import io.github.lilkuzcodev.warfront.civilization.CitizenProfession;
import io.github.lilkuzcodev.warfront.civilization.CivilizationManager;
import io.github.lilkuzcodev.warfront.civilization.CivilizationMath;
import io.github.lilkuzcodev.warfront.entity.ai.CitizenWorkGoal;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
		if (!level().isClientSide()) {
			long wealth = level() instanceof ServerLevel serverLevel
					? io.github.lilkuzcodev.warfront.civilization.EconomyManager.actorMoney(
							serverLevel.getServer(), cityId, serial) : 0L;
			player.sendSystemMessage(Component.literal("Citizen #" + serial + " — " + profession().id()
					+ " of " + cityId + "; wealth=" + wealth + "; goods=" + inventory + "; work=" + workTicks + "/"
					+ CivilizationMath.WORK_CYCLE_TICKS));
		}
		return InteractionResult.SUCCESS;
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
