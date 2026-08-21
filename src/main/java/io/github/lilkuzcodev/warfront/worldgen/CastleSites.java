package io.github.lilkuzcodev.warfront.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lilkuzcodev.warfront.Warfront;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Which castle sites this world has already built.
 *
 * <p>A castle is pasted once and never again. Without a record of that, a player walking
 * back into range would have the castle stamped over the top of itself, erasing anything
 * they had built inside it — the failure would look like vandalism rather than a bug.
 */
public final class CastleSites extends SavedData {

	// key -> "x,y,z" of the origin the castle was actually pasted at. Storing the origin
	// rather than just the key matters: once a castle is standing, the surface heightmap
	// reports the castle's own roof, so nothing can recompute where it was put.
	private static final Codec<CastleSites> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("built", java.util.Map.of())
					.forGetter(sites -> java.util.Map.copyOf(sites.built)),
			Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("templates", java.util.Map.of())
					.forGetter(sites -> java.util.Map.copyOf(sites.templates)),
			Codec.STRING.listOf().optionalFieldOf("dracula_slain", List.of())
					.forGetter(sites -> List.copyOf(sites.draculaSlain))
	).apply(instance, CastleSites::new));

	public static final SavedDataType<CastleSites> TYPE = new SavedDataType<>(
			Warfront.id("castle_sites"), CastleSites::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final java.util.Map<String, String> built = new java.util.HashMap<>();
	// key -> template identifier ("warfront:dracula/castle"), so systems that care which
	// castle stands at a site (the vampire's veil) never have to guess. Sites built before
	// this field existed simply have no entry until something backfills them.
	private final java.util.Map<String, String> templates = new java.util.HashMap<>();
	// Sites whose Dracula was slain by a player's hand. The sun does not count.
	private final java.util.Set<String> draculaSlain = new java.util.HashSet<>();

	public CastleSites() {}

	private CastleSites(java.util.Map<String, String> built, java.util.Map<String, String> templates,
			List<String> draculaSlain) {
		this.built.putAll(built);
		this.templates.putAll(templates);
		this.draculaSlain.addAll(draculaSlain);
	}

	public static CastleSites get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public boolean isBuilt(String key) {
		return built.containsKey(key);
	}

	public void markBuilt(String key, net.minecraft.core.BlockPos origin,
			net.minecraft.resources.Identifier template) {
		built.put(key, origin.getX() + "," + origin.getY() + "," + origin.getZ());
		templates.put(key, template.toString());
		setDirty();
	}

	/** The template pasted at a built site, or null for sites built before this was recorded. */
	public @org.jspecify.annotations.Nullable String template(String key) {
		return templates.get(key);
	}

	public void recordTemplate(String key, net.minecraft.resources.Identifier template) {
		templates.put(key, template.toString());
		setDirty();
	}

	public boolean isDraculaSlain(String key) {
		return draculaSlain.contains(key);
	}

	public void markDraculaSlain(String key) {
		draculaSlain.add(key);
		setDirty();
	}

	/** Where a built castle actually sits, or null if this world has not built it. */
	public net.minecraft.core.@org.jspecify.annotations.Nullable BlockPos origin(String key) {
		String raw = built.get(key);
		if (raw == null) return null;
		String[] parts = raw.split(",");
		if (parts.length != 3) return null;
		return new net.minecraft.core.BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
				Integer.parseInt(parts[2]));
	}

	/** Every built site, for tests and diagnostics. */
	public java.util.Map<String, String> all() {
		return java.util.Map.copyOf(built);
	}

	public int count() {
		return built.size();
	}
}
