package io.github.lilkuzcodev.warfront.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lilkuzcodev.warfront.Warfront;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

/**
 * A random spread that additionally keeps its distance from another structure set, with no
 * cap on how far.
 *
 * <p>Minecraft enforces {@code spacing} and {@code separation} only <em>within</em> a
 * structure set. Nothing in a set's own placement says anything about another set, so the
 * only vanilla tool for cross-set clearance is {@code exclusion_zone} — and its
 * {@code chunk_count} is codec-bounded to {@code [1:16]}, which is 256 blocks. Measured
 * 2026-08-20: a 501-block castle beside a 124-block metropolis needs 313 blocks of
 * centre-to-centre clearance, so the vanilla mechanism cannot express it. The game says so
 * out loud rather than degrading — {@code Value 20 outside of range [1:16]} — which is how
 * this was found.
 *
 * <p>That cap is the whole reason this class exists. Warfront's castles are monumental by
 * intent and a supplied build may be larger than the last one, so the clearance has to be
 * free to grow with them. Everything else is vanilla's: the spread maths is inherited
 * untouched, and the clearance test delegates to
 * {@link ChunkGeneratorStructureState#hasStructureChunkInRange}, which is the same call the
 * capped exclusion zone makes. Only the bound is different.
 *
 * <p>The radius here is a plain number in the JSON rather than something derived at runtime
 * from the castle template, because placement runs long before any structure NBT is read.
 * {@code tools/verify-base-spacing.js} closes that gap from the other side: it re-derives
 * the radius each set actually needs from the NBTs on disk and fails if the configured one
 * is smaller. The code takes a number; the checker proves the number.
 */
public class BaseSpreadPlacement extends RandomSpreadStructurePlacement {

	public static final MapCodec<BaseSpreadPlacement> CODEC = RecordCodecBuilder.mapCodec(
			instance -> placementCodec(instance).and(instance.group(
					Codec.intRange(0, 4096).fieldOf("spacing")
							.forGetter(RandomSpreadStructurePlacement::spacing),
					Codec.intRange(0, 4096).fieldOf("separation")
							.forGetter(RandomSpreadStructurePlacement::separation),
					RandomSpreadType.CODEC.optionalFieldOf("spread_type", RandomSpreadType.LINEAR)
							.forGetter(RandomSpreadStructurePlacement::spreadType),
					StructureSet.CODEC.fieldOf("avoid_set")
							.forGetter(placement -> placement.avoidSet),
					// Deliberately not [1:16]. That bound is the thing this type exists to escape.
					Codec.intRange(1, 4096).fieldOf("avoid_chunks")
							.forGetter(placement -> placement.avoidChunks)))
					.apply(instance, BaseSpreadPlacement::new));

	/** Registered so `"type": "warfront:base_spread"` resolves in a structure set. */
	public static final StructurePlacementType<BaseSpreadPlacement> TYPE = () -> CODEC;

	private final Holder<StructureSet> avoidSet;
	private final int avoidChunks;

	public BaseSpreadPlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod,
			float frequency, int salt, Optional<StructurePlacement.ExclusionZone> exclusionZone,
			int spacing, int separation, RandomSpreadType spreadType,
			Holder<StructureSet> avoidSet, int avoidChunks) {
		super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone,
				spacing, separation, spreadType);
		this.avoidSet = avoidSet;
		this.avoidChunks = avoidChunks;
	}

	/**
	 * Vanilla applies the exclusion zone here, so this is where the uncapped one goes too.
	 * A candidate that vanilla would already reject stays rejected — the extra clearance can
	 * only ever remove placements, never add one.
	 */
	@Override
	public boolean applyInteractionsWithOtherStructures(ChunkGeneratorStructureState state,
			int chunkX, int chunkZ) {
		if (!super.applyInteractionsWithOtherStructures(state, chunkX, chunkZ)) return false;
		return !state.hasStructureChunkInRange(avoidSet, chunkX, chunkZ, avoidChunks);
	}

	@Override
	public StructurePlacementType<?> type() {
		return TYPE;
	}

	public static void register() {
		Registry.register(BuiltInRegistries.STRUCTURE_PLACEMENT, Warfront.id("base_spread"), TYPE);
	}
}
