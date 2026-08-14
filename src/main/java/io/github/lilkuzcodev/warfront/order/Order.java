package io.github.lilkuzcodev.warfront.order;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * The universal command data model (architecture note 3): every commanded action —
 * including Phase 3 player orders — enters the pipeline as one of these.
 *
 * @param issuer     who issued it ("debug", later: player/general UUIDs)
 * @param faction    acting faction id
 * @param objectiveType e.g. "assault" (matched against template capability later)
 * @param target     objective coordinates
 * @param constraints e.g. "no_capture" — templates declare compatibility
 * @param forceCap   max soldiers committed (0 = doctrine default)
 * @param priority   scheduling weight (unused in Phase 1)
 * @param knownIntel intel tokens available to template scoring (empty for now — all estimates)
 */
public record Order(
		String issuer,
		String faction,
		String objectiveType,
		BlockPos target,
		List<String> constraints,
		int forceCap,
		int priority,
		List<String> knownIntel) {
}
