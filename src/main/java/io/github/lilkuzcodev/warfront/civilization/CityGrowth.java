package io.github.lilkuzcodev.warfront.civilization;

import io.github.lilkuzcodev.warfront.Warfront;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.CitizenRecord;
import io.github.lilkuzcodev.warfront.civilization.CivilizationState.CityRecord;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * Births. A settlement grows the way a village does — bounded by the roofs it has and
 * rationed against the food it is holding — rather than by a timer alone.
 *
 * <p>Three gates, all of which a player can influence:
 * <ul>
 *   <li><b>Housing</b> — a city may not exceed its bunk count. Build more, and more
 *       people arrive; burn the barracks down and the town stops growing.</li>
 *   <li><b>Food</b> — the city must be holding a surplus over what its population needs.
 *       A city under siege, blighted, or stripped by raiders does not have children.</li>
 *   <li><b>Solvency</b> — a newborn draws a stake so it starts above the liquidity floor,
 *       and that stake has to come from somewhere real (see
 *       {@link EconomyModel#bringActorToLife}).</li>
 * </ul>
 *
 * <p>Runs from the economic tick on the server thread, so a town keeps growing while
 * nobody is anywhere near it.
 */
public final class CityGrowth {

	/**
	 * Considers one birth for this city. Returns the city unchanged when a gate fails.
	 * At most one citizen is born per call, so growth is a curve rather than a step.
	 */
	public static CityRecord maybeGrow(ServerLevel level, CityRecord city, EconomyModel model) {
		var config = WarfrontRegistry.economy();
		long now = level.getGameTime();
		int population = city.citizens().size();

		if (population >= Math.max(1, city.housing())) {
			return city; // no roof to put them under
		}
		if (population >= WarfrontRegistry.population().citizenHardCap()) {
			return city; // the ceiling that keeps a megacity from becoming a tick budget
		}
		if (model.foodHeld() < (long) population * config.growthFoodPerCitizen()) {
			return city; // not enough in the granary to feed another mouth
		}
		// A deterministic clock per city: no accumulation, so it cannot drift while the
		// chunk is unloaded, and two cities do not give birth in lockstep.
		//
		// This fires on the FIRST economic tick inside each interval window rather than
		// on an exact tick equality. maybeGrow is only reached every
		// gameTicksPerEconomicTick, so demanding `now % interval == 0` meant the clock
		// had to land on one of a handful of reachable residues out of the whole
		// interval — in practice it simply never fired and no town ever grew.
		int period = Math.max(1, config.gameTicksPerEconomicTick());
		long phase = Math.floorMod(city.id().hashCode(), config.growthIntervalTicks());
		if (Math.floorMod(now + phase, config.growthIntervalTicks()) >= period) {
			return city;
		}

		long serial = city.nextSerial();
		int index = Math.toIntExact(serial - 1);
		model.bringActorToLife(index, config.newbornStake());
		// The stake is only worth having if it cleared the floor; if the city could not
		// fund it at all, the birth is abandoned rather than producing a destitute actor.
		if (model.actorMoney(index) <= 0) {
			model.retireActor(index);
			return city;
		}

		CitizenProfession profession = CitizenProfession.values()[(int) (serial % CitizenProfession.values().length)];
		UUID uuid = UUID.nameUUIDFromBytes((level.getSeed() + ":" + city.id() + ":" + serial)
				.getBytes(StandardCharsets.UTF_8));
		// Born at the town centre; the fidelity ladder places them properly on promotion.
		CitizenRecord born = new CitizenRecord(serial, uuid, profession,
				city.center().getX() + 0.5, city.center().getY(), city.center().getZ() + 0.5,
				0L, Map.of(), now, FidelityTier.VIRTUAL, 0L);

		Map<String, CitizenRecord> next = new HashMap<>(city.citizens());
		next.put(Long.toString(serial), born);
		Warfront.LOGGER.debug("{} grew to {} citizens (housing {})", city.id(), next.size(), city.housing());
		return city.withCitizens(next, serial + 1L);
	}

	private CityGrowth() {
	}
}
