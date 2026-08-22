package io.github.lilkuzcodev.warfront.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lilkuzcodev.warfront.Warfront;
import java.util.Optional;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
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
 *
 * <h2>{@code avoid_sets}: other mods' landmarks</h2>
 * Reported from play 2026-08-22: an aegis town generated over a Waldschatten witch hut and
 * left two-thirds of it as stripped birch and stone brick. Structure sets are mutually
 * blind across mods exactly as they are within one, so a base needs to be told what to
 * keep away from. {@code avoid_sets} is a list of {@code {set, chunks}} pairs with the same
 * uncapped clearance test — but referenced by <em>identifier</em>, not by registry holder.
 * A holder reference to {@code waldschatten:witch_huts} would make this mod fail to load
 * any world where waldschatten is absent (the dev server, the batteries, anyone running
 * warfront alone). An identifier is resolved against the world's own structure sets at
 * placement time; a set that is not in this world is simply not there to avoid, logged
 * once. The clearance can only ever remove placements, never add one, so the base yields
 * and the landmark stays — a hut is one-per-patch and irreplaceable; a base is not.
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
					StructureSet.CODEC.optionalFieldOf("avoid_set")
							.forGetter(placement -> placement.avoidSet),
					// Deliberately not [1:16]. That bound is the thing this type exists to escape.
					Codec.intRange(1, 4096).optionalFieldOf("avoid_chunks", 1)
							.forGetter(placement -> placement.avoidChunks),
					AvoidSet.CODEC.listOf().optionalFieldOf("avoid_sets", List.of())
							.forGetter(placement -> placement.avoidSets)))
					.apply(instance, BaseSpreadPlacement::new));

	/** A structure set to keep clear of, named by identifier so its absence is not an error. */
	public record AvoidSet(Identifier set, int chunks) {
		public static final Codec<AvoidSet> CODEC = RecordCodecBuilder.create(i -> i.group(
				Identifier.CODEC.fieldOf("set").forGetter(AvoidSet::set),
				Codec.intRange(1, 4096).fieldOf("chunks").forGetter(AvoidSet::chunks))
				.apply(i, AvoidSet::new));

		ResourceKey<StructureSet> key() {
			return ResourceKey.create(Registries.STRUCTURE_SET, set);
		}
	}

	private static final Set<Identifier> MISSING_WARNED = new HashSet<>();

	/** Registered so `"type": "warfront:base_spread"` resolves in a structure set. */
	public static final StructurePlacementType<BaseSpreadPlacement> TYPE = () -> CODEC;

	private final Optional<Holder<StructureSet>> avoidSet;
	private final int avoidChunks;
	private final List<AvoidSet> avoidSets;

	public BaseSpreadPlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod,
			float frequency, int salt, Optional<StructurePlacement.ExclusionZone> exclusionZone,
			int spacing, int separation, RandomSpreadType spreadType,
			Optional<Holder<StructureSet>> avoidSet, int avoidChunks, List<AvoidSet> avoidSets) {
		super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone,
				spacing, separation, spreadType);
		this.avoidSet = avoidSet;
		this.avoidChunks = avoidChunks;
		this.avoidSets = avoidSets;
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
		if (avoidSet.isPresent() && state.hasStructureChunkInRange(avoidSet.get(), chunkX, chunkZ, avoidChunks)) {
			return false;
		}
		for (AvoidSet avoid : avoidSets) {
			Holder<StructureSet> resolved = null;
			for (Holder<StructureSet> candidate : state.possibleStructureSets()) {
				if (candidate.is(avoid.key())) {
					resolved = candidate;
					break;
				}
			}
			if (resolved == null) {
				if (MISSING_WARNED.add(avoid.set())) {
					Warfront.LOGGER.info("avoid_sets: {} is not a structure set in this world; nothing to keep clear of",
							avoid.set());
				}
				continue;
			}
			if (state.hasStructureChunkInRange(resolved, chunkX, chunkZ, avoid.chunks())) return false;
		}
		return true;
	}

	@Override
	public StructurePlacementType<?> type() {
		return TYPE;
	}

	public static void register() {
		Registry.register(BuiltInRegistries.STRUCTURE_PLACEMENT, Warfront.id("base_spread"), TYPE);
	}
}
