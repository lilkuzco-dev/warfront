package io.github.lilkuzcodev.warfront.entity;

import io.github.lilkuzcodev.warfront.Warfront;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * The singular lord of the rare Dracula castle.
 *
 * <p>Traditional vampire rules are mechanical rather than flavor text: direct sun
 * burns him, water harms him, darkness regenerates him, successful bites steal life,
 * and wooden swords multiply incoming damage sevenfold. He otherwise has boss-grade
 * health, armor, speed, reach and knockback resistance.
 */
public final class DraculaEntity extends PathfinderMob implements Enemy {
	public static final float WOODEN_SWORD_MULTIPLIER = 7.0F;

	public DraculaEntity(EntityType<? extends DraculaEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setCustomName(Component.literal("Count Dracula"));
		setCustomNameVisible(true);
		setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
		setDropChance(EquipmentSlot.MAINHAND, 0.0F);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 240.0)
				.add(Attributes.MOVEMENT_SPEED, 0.38)
				.add(Attributes.ATTACK_DAMAGE, 16.0)
				.add(Attributes.FOLLOW_RANGE, 56.0)
				.add(Attributes.ARMOR, 14.0)
				.add(Attributes.ATTACK_KNOCKBACK, 1.5)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.65);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));
		goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8));
		goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 16.0F));
		goalSelector.addGoal(8, new RandomLookAroundGoal(this));
		targetSelector.addGoal(1, new HurtByTargetGoal(this));
		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
		targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, CitizenEntity.class, true));
		targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, SoldierEntity.class, true));
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		Entity attacker = source.getEntity();
		if (attacker instanceof LivingEntity living && living.getMainHandItem().is(Items.WOODEN_SWORD)) {
			amount *= WOODEN_SWORD_MULTIPLIER;
		}
		return super.hurtServer(level, source, amount);
	}

	@Override
	public boolean doHurtTarget(ServerLevel level, Entity target) {
		boolean hit = super.doHurtTarget(level, target);
		if (hit) heal(6.0F);
		return hit;
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel serverLevel) || tickCount % 20 != 0) return;
		long time = level().getOverworldClockTime() % 24000L;
		boolean directSun = time < 12000L && serverLevel.canSeeSky(blockPosition());
		if (directSun) {
			hurtServer(serverLevel, damageSources().onFire(), 6.0F);
		} else if (isInWater()) {
			hurtServer(serverLevel, damageSources().drown(), 4.0F);
		} else if (time >= 13000L && getHealth() < getMaxHealth()) {
			heal(2.0F);
		}
	}

	@Override
	public void die(DamageSource source) {
		// Only a player's hand ends the Count for good; the sun and the sea merely send
		// him back to his coffin until the next visitor.
		if (level() instanceof ServerLevel serverLevel
				&& getKillCredit() instanceof Player) {
			io.github.lilkuzcodev.warfront.systems.VampireVeil.onDraculaSlainByPlayer(serverLevel, blockPosition());
		}
		super.die(source);
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
		super.dropCustomDeathLoot(level, source, recentlyHit);
		spawnAtLocation(level, new ItemStack(Items.NETHER_STAR, 2));
		spawnAtLocation(level, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2));
		dropOptional(level, "vibranium:vibranium_ingot", 3);
		dropOptional(level, "vibranium:godite_ingot", 1);
	}

	private void dropOptional(ServerLevel level, String id, int count) {
		BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
				.ifPresent(item -> spawnAtLocation(level, new ItemStack(item, count)));
	}

	public static Identifier texture() {
		return Warfront.id("textures/entity/dracula.png");
	}
}
