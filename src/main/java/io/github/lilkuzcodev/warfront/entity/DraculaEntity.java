package io.github.lilkuzcodev.warfront.entity;

import io.github.lilkuzcodev.warfront.Warfront;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
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
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The singular lord of the rare Dracula castle.
 *
 * <p>Traditional vampire rules are mechanical rather than flavor text: direct sun
 * burns him, water harms him, darkness regenerates him, bites steal life and curse the
 * bitten, and wooden swords multiply incoming damage sevenfold. Steel and arrows barely
 * touch him. Beyond that he has four powers, each announced by sound and smoke:
 * <ul>
 *   <li><b>Shadow step</b> — a target out of reach or out of sight is closed on instantly;
 *       he steps out of the dark beside them (never into direct sun by day).</li>
 *   <li><b>The swarm</b> — bats burst around his quarry, who is slowed and blinded.</li>
 *   <li><b>Mist form</b> — wounded below two fifths, he dissolves and mends.</li>
 *   <li><b>Dread</b> — darkness presses on anyone within five blocks of him.</li>
 * </ul>
 * A boss bar darkens the sky for everyone within sixty-four blocks. Reported from play as
 * "make him extremely strong and menacing"; the numbers are the knobs at the top.
 */
public final class DraculaEntity extends PathfinderMob implements Enemy {
	public static final float WOODEN_SWORD_MULTIPLIER = 7.0F;
	/** Arrows, tridents and the like land at this fraction. */
	public static final float PROJECTILE_MULTIPLIER = 0.35F;
	/** A target this far away, or unseen, is shadow-stepped to. */
	private static final double STEP_RANGE = 7.0;
	private static final int STEP_COOLDOWN = 60;
	private static final int SWARM_COOLDOWN = 240;
	private static final int SWARM_BATS = 8;
	private static final int MIST_COOLDOWN = 900;
	private static final float MIST_BELOW = 0.4F;
	private static final double DREAD_RADIUS = 5.0;
	private static final double BOSS_BAR_RANGE = 64.0;

	private final ServerBossEvent bossEvent = new ServerBossEvent(UUID.randomUUID(),
			Component.translatable("entity.warfront.dracula"), BossEvent.BossBarColor.RED,
			BossEvent.BossBarOverlay.NOTCHED_10);
	private int stepCooldown;
	private int swarmCooldown;
	private int mistCooldown;

	public DraculaEntity(EntityType<? extends DraculaEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setCustomName(Component.translatable("entity.warfront.dracula"));
		setCustomNameVisible(true);
		setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
		setDropChance(EquipmentSlot.MAINHAND, 0.0F);
		bossEvent.setDarkenScreen(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 600.0)
				.add(Attributes.MOVEMENT_SPEED, 0.42)
				.add(Attributes.ATTACK_DAMAGE, 24.0)
				.add(Attributes.FOLLOW_RANGE, 96.0)
				.add(Attributes.ARMOR, 18.0)
				.add(Attributes.ARMOR_TOUGHNESS, 8.0)
				.add(Attributes.ATTACK_KNOCKBACK, 2.5)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));
		goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8));
		goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 16.0F));
		goalSelector.addGoal(8, new RandomLookAroundGoal(this));
		targetSelector.addGoal(1, new HurtByTargetGoal(this));
		// mustSee=false: he knows where the living are in his own halls; walls do not hide
		// a mortal from him, and shadow step closes whatever the path cannot.
		targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, false));
		targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, CitizenEntity.class, true));
		targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, SoldierEntity.class, true));
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.is(DamageTypeTags.IS_FALL)) return false;
		Entity attacker = source.getEntity();
		if (attacker instanceof LivingEntity living && living.getMainHandItem().is(Items.WOODEN_SWORD)) {
			amount *= WOODEN_SWORD_MULTIPLIER;
		} else if (source.is(DamageTypeTags.IS_PROJECTILE)) {
			amount *= PROJECTILE_MULTIPLIER;
		}
		return super.hurtServer(level, source, amount);
	}

	/** The bite: life stolen, and the bitten left in darkness, weakness and decay. */
	@Override
	public boolean doHurtTarget(ServerLevel level, Entity target) {
		boolean hit = super.doHurtTarget(level, target);
		if (hit) {
			heal(12.0F);
			if (target instanceof LivingEntity living) {
				living.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0), this);
				living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0), this);
				living.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0), this);
			}
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1.2F, 0.6F);
		}
		return hit;
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel serverLevel)) return;
		if (stepCooldown > 0) stepCooldown--;
		if (swarmCooldown > 0) swarmCooldown--;
		if (mistCooldown > 0) mistCooldown--;
		LivingEntity target = getTarget();
		if (target != null && target.isAlive()) {
			double distance = distanceTo(target);
			if (stepCooldown == 0 && (distance > STEP_RANGE || !hasLineOfSight(target))
					&& shadowStepTo(serverLevel, target, 2.0, 3.5)) {
				stepCooldown = STEP_COOLDOWN;
			}
			if (swarmCooldown == 0 && distance < 24.0) {
				summonSwarm(serverLevel, target);
				swarmCooldown = SWARM_COOLDOWN;
			}
		}
		if (tickCount % 20 != 0) return;
		bossEvent.setProgress(getHealth() / getMaxHealth());
		for (ServerPlayer player : serverLevel.players()) {
			if (player.distanceToSqr(this) < BOSS_BAR_RANGE * BOSS_BAR_RANGE) {
				bossEvent.addPlayer(player);
			} else {
				bossEvent.removePlayer(player);
			}
		}
		for (Player player : serverLevel.getEntitiesOfClass(Player.class, getBoundingBox().inflate(DREAD_RADIUS))) {
			if (!player.isSpectator() && !player.isCreative()) {
				player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 0, false, false), this);
			}
		}
		long time = level().getOverworldClockTime() % 24000L;
		boolean directSun = time < 12000L && serverLevel.canSeeSky(blockPosition());
		if (directSun) {
			hurtServer(serverLevel, damageSources().onFire(), 6.0F);
		} else if (isInWater()) {
			hurtServer(serverLevel, damageSources().drown(), 4.0F);
		} else if (time >= 13000L && getHealth() < getMaxHealth()) {
			heal(4.0F);
		}
		if (mistCooldown == 0 && getHealth() < getMaxHealth() * MIST_BELOW) {
			mistForm(serverLevel);
			mistCooldown = MIST_COOLDOWN;
		}
	}

	/**
	 * Steps out of the dark beside {@code near}, between {@code minRadius} and
	 * {@code maxRadius} away, on a standable spot — never under open sky while the real
	 * sun is up. Returns false when no such spot exists. Public: the veil uses it to bring
	 * the Count to a visitor anywhere in his grounds.
	 */
	public boolean shadowStepTo(ServerLevel level, Entity near, double minRadius, double maxRadius) {
		RandomSource random = getRandom();
		boolean day = level.getOverworldClockTime() % 24000L < 12000L;
		for (int attempt = 0; attempt < 24; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);
			double x = near.getX() + Math.cos(angle) * radius;
			double z = near.getZ() + Math.sin(angle) * radius;
			BlockPos spot = standableNear(level, BlockPos.containing(x, near.getY(), z));
			if (spot == null) continue;
			if (day && level.canSeeSky(spot)) continue;
			Vec3 from = position();
			if (!randomTeleport(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, false)) continue;
			getNavigation().stop();
			lookAt(near, 360.0F, 360.0F);
			shadowBurst(level, from);
			shadowBurst(level, position());
			level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.5F);
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.BAT_TAKEOFF, SoundSource.HOSTILE, 1.5F, 0.7F);
			return true;
		}
		return false;
	}

	private static BlockPos standableNear(ServerLevel level, BlockPos centre) {
		// Castle floors sit at odd heights around a quarry that may be on a stair or a
		// rampart; eight blocks either way covers a storey above and a storey below.
		for (int dy : new int[] { 0, -1, 1, -2, 2, -3, 3, -4, 4, -5, 5, -6, 6, -7, 7, -8, 8 }) {
			BlockPos pos = centre.above(dy);
			if (!level.isLoaded(pos)) continue;
			if (level.getBlockState(pos.below()).isSolid()
					&& level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()) {
				return pos;
			}
		}
		return null;
	}

	private static void shadowBurst(ServerLevel level, Vec3 at) {
		level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1.0, at.z, 30, 0.4, 0.7, 0.4, 0.02);
		level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 1.0, at.z, 10, 0.3, 0.6, 0.3, 0.01);
	}

	/** Bats burst around the quarry; the swarm drags at them and blinds them. */
	private void summonSwarm(ServerLevel level, LivingEntity target) {
		RandomSource random = getRandom();
		for (int i = 0; i < SWARM_BATS; i++) {
			Bat bat = batType().create(level, EntitySpawnReason.MOB_SUMMONED);
			if (bat == null) break;
			bat.snapTo(target.getX() + (random.nextDouble() - 0.5) * 4.0, target.getY() + 1.0 + random.nextDouble() * 2.0,
					target.getZ() + (random.nextDouble() - 0.5) * 4.0, random.nextFloat() * 360.0F, 0.0F);
			level.addFreshEntity(bat);
		}
		target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1), this);
		target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0), this);
		level.sendParticles(ParticleTypes.ASH, target.getX(), target.getY() + 1.0, target.getZ(), 40, 1.0, 1.0, 1.0, 0.05);
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BAT_TAKEOFF, SoundSource.HOSTILE, 2.0F, 0.8F);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.0F, 0.5F);
	}

	/** Wounded, he dissolves into mist and mends. */
	private void mistForm(ServerLevel level) {
		addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 2));
		addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 1));
		addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 1));
		level.sendParticles(ParticleTypes.ASH, getX(), getY() + 1.0, getZ(), 80, 0.8, 1.0, 0.8, 0.03);
		level.sendParticles(ParticleTypes.WITCH, getX(), getY() + 1.0, getZ(), 20, 0.5, 0.8, 0.5, 0.0);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.5F, 0.8F);
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.PHANTOM_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.WARDEN_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.WARDEN_DEATH;
	}

	@Override
	public float getVoicePitch() {
		return 0.6F;
	}

	@Override
	public int getAmbientSoundInterval() {
		return 160;
	}

	@Override
	public void die(DamageSource source) {
		// Only a player's hand ends the Count for good; the sun and the sea merely send
		// him back to his coffin until the next visitor.
		if (level() instanceof ServerLevel serverLevel) {
			if (getKillCredit() instanceof Player) {
				io.github.lilkuzcodev.warfront.systems.VampireVeil.onDraculaSlainByPlayer(serverLevel, blockPosition());
			}
			// He leaves as bats.
			RandomSource random = getRandom();
			for (int i = 0; i < 12; i++) {
				Bat bat = batType().create(serverLevel, EntitySpawnReason.MOB_SUMMONED);
				if (bat == null) break;
				bat.snapTo(getX() + (random.nextDouble() - 0.5) * 2.0, getY() + 1.0 + random.nextDouble(),
						getZ() + (random.nextDouble() - 0.5) * 2.0, random.nextFloat() * 360.0F, 0.0F);
				serverLevel.addFreshEntity(bat);
			}
			shadowBurst(serverLevel, position());
		}
		bossEvent.removeAllPlayers();
		super.die(source);
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		bossEvent.removeAllPlayers();
		super.remove(reason);
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

	/** Vanilla's bat type, by registry id: 26.2 keeps no EntityType.BAT constant. */
	@SuppressWarnings("unchecked")
	public static EntityType<Bat> batType() {
		return (EntityType<Bat>) BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse("minecraft:bat")).orElseThrow();
	}

	public static Identifier texture() {
		return Warfront.id("textures/entity/dracula.png");
	}
}
