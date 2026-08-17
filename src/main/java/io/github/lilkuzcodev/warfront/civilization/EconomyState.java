package io.github.lilkuzcodev.warfront.civilization;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lilkuzcodev.warfront.Warfront;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Compact persisted snapshots for deterministic city economies. */
public final class EconomyState extends SavedData {
	private static final Codec<Map<String, String>> SNAPSHOTS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING);
	private static final Codec<EconomyState> CODEC = RecordCodecBuilder.create(i -> i.group(
			SNAPSHOTS_CODEC.optionalFieldOf("city_snapshots", Map.of()).forGetter(s -> Map.copyOf(s.snapshots))
	).apply(i, EconomyState::new));

	public static final SavedDataType<EconomyState> TYPE = new SavedDataType<>(
			Warfront.id("economy"), EconomyState::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<String, String> snapshots = new HashMap<>();

	public EconomyState() {}
	private EconomyState(Map<String, String> snapshots) { this.snapshots.putAll(snapshots); }

	public static EconomyState get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public String snapshot(String cityId) { return snapshots.get(cityId); }
	public void put(String cityId, EconomyModel model) {
		snapshots.put(cityId, model.encode());
		setDirty();
	}
}
