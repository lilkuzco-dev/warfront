package io.github.lilkuzcodev.warfront.civilization;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lilkuzcodev.warfront.Warfront;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent, entity-independent city and citizen state. */
public final class CivilizationState extends SavedData {
	private static final Codec<Map<String, Integer>> INVENTORY_CODEC =
			Codec.unboundedMap(Codec.STRING, Codec.INT);
	private static final Codec<Map<String, CitizenRecord>> CITIZENS_CODEC =
			Codec.unboundedMap(Codec.STRING, CitizenRecord.CODEC);
	private static final Codec<Map<String, CityRecord>> CITIES_CODEC =
			Codec.unboundedMap(Codec.STRING, CityRecord.CODEC);
	private static final Codec<Map<String, String>> SOLDIER_CITY_CODEC =
			Codec.unboundedMap(Codec.STRING, Codec.STRING);

	/** One party out of town. At most one per city, keyed by the city it left. */
	public record Expedition(String cityId, String kind, String targetCityId, int party,
			long departTick, long returnTick, long seed) {
		private static final Codec<Expedition> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("city").forGetter(Expedition::cityId),
				Codec.STRING.fieldOf("kind").forGetter(Expedition::kind),
				Codec.STRING.optionalFieldOf("target", "").forGetter(Expedition::targetCityId),
				Codec.INT.fieldOf("party").forGetter(Expedition::party),
				Codec.LONG.fieldOf("depart").forGetter(Expedition::departTick),
				Codec.LONG.fieldOf("return").forGetter(Expedition::returnTick),
				Codec.LONG.fieldOf("seed").forGetter(Expedition::seed)
		).apply(i, Expedition::new));
	}

	private static final Codec<Map<String, Expedition>> EXPEDITIONS_CODEC =
			Codec.unboundedMap(Codec.STRING, Expedition.CODEC);

	public record CitizenRecord(long serial, UUID entityId, CitizenProfession profession,
			double x, double y, double z, long workTicks, Map<String, Integer> inventory,
			long lastAdvancedTick, FidelityTier tier, long awayUntilTick) {

		/** Away on an expedition, and therefore not standing in the town. */
		public boolean isAway(long now) {
			return awayUntilTick > now;
		}
		private static final Codec<CitizenRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("serial").forGetter(CitizenRecord::serial),
				Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("entity_id").forGetter(CitizenRecord::entityId),
				Codec.STRING.xmap(CitizenProfession::byId, CitizenProfession::id).fieldOf("profession").forGetter(CitizenRecord::profession),
				Codec.DOUBLE.fieldOf("x").forGetter(CitizenRecord::x),
				Codec.DOUBLE.fieldOf("y").forGetter(CitizenRecord::y),
				Codec.DOUBLE.fieldOf("z").forGetter(CitizenRecord::z),
				Codec.LONG.fieldOf("work_ticks").forGetter(CitizenRecord::workTicks),
				INVENTORY_CODEC.fieldOf("inventory").forGetter(CitizenRecord::inventory),
				Codec.LONG.fieldOf("last_advanced").forGetter(CitizenRecord::lastAdvancedTick),
				Codec.STRING.xmap(FidelityTier::byId, FidelityTier::id).fieldOf("tier").forGetter(CitizenRecord::tier),
				Codec.LONG.optionalFieldOf("away_until", 0L).forGetter(CitizenRecord::awayUntilTick)
		).apply(i, CitizenRecord::new));

		public CitizenRecord withState(double newX, double newY, double newZ, long newWorkTicks,
				Map<String, Integer> newInventory, long now, FidelityTier newTier) {
			return new CitizenRecord(serial, entityId, profession, newX, newY, newZ, newWorkTicks,
					Map.copyOf(newInventory), now, newTier, awayUntilTick);
		}

		public CitizenRecord withAwayUntil(long tick) {
			return new CitizenRecord(serial, entityId, profession, x, y, z, workTicks, inventory,
					lastAdvancedTick, tier, tick);
		}
	}

	public record CityRecord(String id, String faction, BlockPos center, int radius, long nextSerial,
			Map<String, CitizenRecord> citizens, int housing, long lastExpeditionTick) {

		/** Convenience for the many call sites that only replace the roster. */
		public CityRecord withCitizens(Map<String, CitizenRecord> next, long nextSerialValue) {
			return new CityRecord(id, faction, center, radius, nextSerialValue, Map.copyOf(next),
					housing, lastExpeditionTick);
		}

		public CityRecord withHousing(int value) {
			return new CityRecord(id, faction, center, radius, nextSerial, citizens, value, lastExpeditionTick);
		}

		public CityRecord withLastExpedition(long tick) {
			return new CityRecord(id, faction, center, radius, nextSerial, citizens, housing, tick);
		}
		private static final Codec<CityRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("id").forGetter(CityRecord::id),
				Codec.STRING.fieldOf("faction").forGetter(CityRecord::faction),
				BlockPos.CODEC.fieldOf("center").forGetter(CityRecord::center),
				Codec.INT.fieldOf("radius").forGetter(CityRecord::radius),
				Codec.LONG.fieldOf("next_serial").forGetter(CityRecord::nextSerial),
				CITIZENS_CODEC.fieldOf("citizens").forGetter(CityRecord::citizens),
				Codec.INT.optionalFieldOf("housing", 0).forGetter(CityRecord::housing),
				Codec.LONG.optionalFieldOf("last_expedition", 0L).forGetter(CityRecord::lastExpeditionTick)
		).apply(i, CityRecord::new));
	}

	private static final Codec<CivilizationState> CODEC = RecordCodecBuilder.create(i -> i.group(
			CITIES_CODEC.optionalFieldOf("cities", Map.of()).forGetter(s -> Map.copyOf(s.cities)),
			SOLDIER_CITY_CODEC.optionalFieldOf("soldier_cities", Map.of()).forGetter(s -> Map.copyOf(s.soldierCities)),
			EXPEDITIONS_CODEC.optionalFieldOf("expeditions", Map.of()).forGetter(s -> Map.copyOf(s.expeditions))
	).apply(i, CivilizationState::new));

	public static final SavedDataType<CivilizationState> TYPE = new SavedDataType<>(
			Warfront.id("civilization"), CivilizationState::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<String, CityRecord> cities = new HashMap<>();
	private final Map<String, String> soldierCities = new HashMap<>();
	private final Map<String, Expedition> expeditions = new HashMap<>();

	public CivilizationState() {}

	private CivilizationState(Map<String, CityRecord> cities, Map<String, String> soldierCities,
			Map<String, Expedition> expeditions) {
		this.cities.putAll(cities);
		this.soldierCities.putAll(soldierCities);
		this.expeditions.putAll(expeditions);
	}

	public Map<String, Expedition> expeditions() { return Map.copyOf(expeditions); }

	public Expedition expeditionFor(String cityId) { return expeditions.get(cityId); }

	public void putExpedition(Expedition expedition) {
		expeditions.put(expedition.cityId(), expedition);
		setDirty();
	}

	public void removeExpedition(String cityId) {
		if (expeditions.remove(cityId) != null) setDirty();
	}

	public static CivilizationState get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Map<String, CityRecord> cities() { return Map.copyOf(cities); }
	public CityRecord city(String id) { return cities.get(id); }

	public void putCity(CityRecord city) {
		cities.put(city.id(), city);
		setDirty();
	}

	public void removeCitizen(String cityId, long serial) {
		CityRecord city = cities.get(cityId);
		if (city == null || !city.citizens().containsKey(Long.toString(serial))) return;
		Map<String, CitizenRecord> next = new HashMap<>(city.citizens());
		next.remove(Long.toString(serial));
		putCity(city.withCitizens(next, city.nextSerial()));
	}

	public void assignSoldier(UUID soldier, String cityId) {
		if (!cityId.equals(soldierCities.put(soldier.toString(), cityId))) setDirty();
	}

	public long assignedSoldierCount(String cityId) {
		return soldierCities.values().stream().filter(cityId::equals).count();
	}
}
