package io.github.lilkuzcodev.warfront.dialogue;

import io.github.lilkuzcodev.warfront.data.DispositionConfig;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Option selection (Stage 4B.3): filter by conditions → score (weight × novelty) →
 * pick 4 with a category-spread constraint and a guaranteed exit/neutral option.
 */
public final class DialogueEngine {
	public static final int SHOWN_HISTORY_CAP = 30;
	private static final int PICK = 4;
	private static final float NOVELTY_PENALTY = 0.15F;

	/** Everything conditions can see, computed once per selection. */
	public record Context(String faction, String standing, String band, String bandGroup, String role,
			String location, String time, boolean recentCombat, boolean hasKilledThisFaction,
			String contractState, int techLevel, ServerPlayer player) {

		public static Context of(ServerPlayer player, SoldierEntity soldier) {
			ServerLevel level = (ServerLevel) soldier.level();
			WarfrontState state = WarfrontState.get(level.getServer());
			String faction = soldier.getFaction();
			long now = level.getGameTime();
			float standingValue = state.standing(player.getUUID(), faction);
			String band = state.dispositionBand(player.getUUID(), faction, now);
			String role = switch (soldier.getRank()) {
				case "officer" -> "officer";
				case "quartermaster" -> "quartermaster";
				default -> "grunt";
			};
			String location = !soldier.getBaseKey().isEmpty() ? "in_base"
					: soldier.getSquadId() != null || soldier.isRoaming() ? "patrol" : "wilderness";
			long dayTime = level.getOverworldClockTime() % 24000L;
			boolean night = dayTime >= 13000L && dayTime <= 23000L;
			boolean recentCombat = soldier.getLastHurtByMob() != null
					|| player.getLastHurtByMob() != null || player.getLastHurtMob() != null;
			WarfrontState.Contract contract = state.contract(player.getUUID(), faction);
			String contractState = contract == null ? "none"
					: "offered".equals(contract.state()) ? "offered"
					: WorkOrders.isComplete(state, player, contract) ? "complete_ready" : "active";
			return new Context(faction, WarfrontRegistry.standing().label(standingValue), band,
					DispositionConfig.bandGroup(band), role, location, night ? "night" : "day", recentCombat,
					state.remembers(player.getUUID(), faction, "killed_soldier", now), contractState,
					state.techLevel(faction), player);
		}
	}

	public static boolean matches(DialogueOption option, Context ctx) {
		DialogueOption.Conditions c = option.conditions();
		if (!c.factions().isEmpty() && !c.factions().contains(ctx.faction())) {
			return false;
		}
		if (!c.standings().isEmpty() && !c.standings().contains(ctx.standing())) {
			return false;
		}
		if (!c.dispositions().isEmpty() && !c.dispositions().contains(ctx.band())
				&& !c.dispositions().contains(ctx.bandGroup())) {
			return false;
		}
		if (!c.roles().isEmpty() && !c.roles().contains(ctx.role())) {
			return false;
		}
		if (!c.locations().isEmpty() && !c.locations().contains(ctx.location())) {
			return false;
		}
		if (!c.times().isEmpty() && !c.times().contains(ctx.time())) {
			return false;
		}
		if (c.recentCombat() != null && c.recentCombat() != ctx.recentCombat()) {
			return false;
		}
		if (c.hasKilledThisFaction() != null && c.hasKilledThisFaction() != ctx.hasKilledThisFaction()) {
			return false;
		}
		if (!c.contractStates().isEmpty() && !c.contractStates().contains(ctx.contractState())) {
			return false;
		}
		if (ctx.techLevel() < c.techMin() || ctx.techLevel() > c.techMax()) {
			return false;
		}
		if (!c.requiresItem().isEmpty() && countItems(ctx.player(), c.requiresItem()) < c.requiresCount()) {
			return false;
		}
		return true;
	}

	static int countItems(ServerPlayer player, String itemId) {
		int total = 0;
		for (ItemStack stack : player.getInventory()) {
			if (!stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
					.toString().equals(itemId)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Picks up to 4 options: highest score first, never 4 of one category, always at
	 * least one exit/neutral option. Excluded ids (already shown this conversation)
	 * never reappear; the persistent recent-history applies a novelty penalty.
	 */
	public static List<DialogueOption> select(Context ctx, WarfrontState state, Set<String> excluded, long now) {
		List<String> recent = state.recentShown(ctx.player().getUUID());
		record Scored(DialogueOption option, double score) {
		}
		List<Scored> eligible = new ArrayList<>();
		var random = ctx.player().level().getRandom();
		for (DialogueOption option : DialogueRegistry.options().values()) {
			if (excluded.contains(option.id()) || !matches(option, ctx)) {
				continue;
			}
			long last = state.lastUsed(ctx.player().getUUID(), option.id());
			if ("ever".equals(option.oncePer()) && last != Long.MIN_VALUE) {
				continue;
			}
			if ("day".equals(option.oncePer()) && last != Long.MIN_VALUE && now - last < 24000) {
				continue;
			}
			if (option.cooldownMinutes() > 0 && last != Long.MIN_VALUE
					&& now - last < option.cooldownMinutes() * 1200L) {
				continue;
			}
			double score = option.weight() * (recent.contains(option.id()) ? NOVELTY_PENALTY : 1.0)
					* (0.75 + random.nextDouble() * 0.5);
			eligible.add(new Scored(option, score));
		}
		eligible.sort(Comparator.comparingDouble(Scored::score).reversed());

		List<DialogueOption> picked = new ArrayList<>();
		Set<String> categories = new HashSet<>();
		int categoryRepeats = 0;
		for (Scored scored : eligible) {
			if (picked.size() >= PICK) {
				break;
			}
			// spread: at most 2 of any one category among the four
			long sameCategory = picked.stream().filter(p -> p.category().equals(scored.option().category())).count();
			if (sameCategory >= 2) {
				continue;
			}
			picked.add(scored.option());
			categories.add(scored.option().category());
			if (sameCategory > 0) {
				categoryRepeats++;
			}
		}
		// guarantee an exit/neutral option in the set
		if (picked.stream().noneMatch(DialogueOption::exit)) {
			eligible.stream().map(Scored::option).filter(DialogueOption::exit)
					.filter(o -> !picked.contains(o)).findFirst().ifPresent(exit -> {
						if (picked.size() >= PICK) {
							picked.remove(picked.size() - 1);
						}
						picked.add(exit);
					});
		}
		return picked;
	}

	private DialogueEngine() {
	}
}
