package io.github.lilkuzcodev.warfront.dialogue;

import io.github.lilkuzcodev.warfront.data.DispositionConfig;
import io.github.lilkuzcodev.warfront.data.WarfrontRegistry;
import io.github.lilkuzcodev.warfront.data.WarfrontState;
import io.github.lilkuzcodev.warfront.entity.SoldierEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Option selection (Stage 4B.3): filter by conditions → score (weight × novelty) →
 * pick a friendly, neutral, threatening, and safe-exit option with category spread.
 */
public final class DialogueEngine {
	public static final int SHOWN_HISTORY_CAP = 200;
	private static final int PICK = 4;
	private record Scored(DialogueOption option, double score) {
	}

	/** Everything conditions can see, computed once per selection. */
	public record Context(String faction, String standing, String band, String bandGroup, String role, String personality,
			String location, String time, boolean recentCombat, boolean hasKilledThisFaction,
			String contractState, int techLevel, ServerPlayer player) {

		public static Context of(ServerPlayer player, SoldierEntity soldier) {
			ServerLevel level = (ServerLevel) soldier.level();
			WarfrontState state = WarfrontState.get(level.getServer());
			String faction = soldier.getFaction();
			long now = WarfrontState.clock(level);
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
					DispositionConfig.bandGroup(band), role, soldier.dialoguePersonality(), location,
					night ? "night" : "day", recentCombat,
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
		if (!c.personalities().isEmpty() && !c.personalities().contains(ctx.personality())) {
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
	 * Picks up to 4 options: one per gameplay tone, with distinct categories when possible.
	 * Excluded ids (already shown this conversation)
	 * never reappear. Fresh options are always considered before anything in the
	 * persistent recent-history; recent lines are only a fallback when a narrow
	 * context has exhausted every fresh match.
	 */
	public static List<DialogueOption> select(Context ctx, WarfrontState state, Set<String> excluded, long now,
			String activeBranch, int activeDepth) {
		List<String> recent = state.recentShown(ctx.player().getUUID());
		List<Scored> fresh = new ArrayList<>();
		List<Scored> recentFallback = new ArrayList<>();
		var random = ctx.player().level().getRandom();
		for (DialogueOption option : DialogueRegistry.options().values()) {
			if (excluded.contains(option.id()) || !matches(option, ctx)) {
				continue;
			}
			boolean safeExit = option.exit()
					&& option.effects().stream().anyMatch(effect -> "end".equals(effect.type()));
			if (activeBranch.isEmpty()) {
				if (!option.branch().isEmpty() && option.branchDepth() != 0) continue;
			} else if (!safeExit
					&& (!activeBranch.equals(option.branch()) || option.branchDepth() != activeDepth)) {
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
			// context relevance: options written FOR this band/situation outrank generic
			// chatter (a vengeful-band grunt leads with warnings, not small talk)
			double relevance = 1.0;
			DialogueOption.Conditions c = option.conditions();
			if (!c.dispositions().isEmpty()) {
				relevance *= 2.5;
			}
			if (!c.locations().isEmpty() || !c.times().isEmpty() || c.recentCombat() != null
					|| c.hasKilledThisFaction() != null || !c.contractStates().isEmpty()) {
				relevance *= 1.5;
			}
			double score = option.weight() * relevance * (0.75 + random.nextDouble() * 0.5);
			(recent.contains(option.id()) ? recentFallback : fresh).add(new Scored(option, score));
		}
		Comparator<Scored> byScore = Comparator.comparingDouble(Scored::score).reversed();
		fresh.sort(byScore);
		recentFallback.sort(byScore);
		List<Scored> eligible = new ArrayList<>(fresh.size() + recentFallback.size());
		eligible.addAll(fresh);
		eligible.addAll(recentFallback);

		List<DialogueOption> picked = new ArrayList<>();
		// Every set communicates its consequences clearly: one friendly choice, one
		// informational choice, one hostile choice, and one safe way out.
		for (String tone : List.of("positive", "neutral", "negative")) {
			pickTone(eligible, picked, tone);
		}
		// Root menus always expose a safe, neutral doorway into one of the long-form
		// topics instead of relying on random scoring to make branches discoverable.
		if (activeBranch.isEmpty()) {
			eligible.stream().map(Scored::option)
					.filter(o -> !o.branch().isEmpty() && o.branchDepth() == 0 && "neutral".equals(o.tone()))
					.findFirst().ifPresent(branchEntry -> {
						picked.removeIf(option -> "neutral".equals(option.tone()));
						picked.add(Math.min(1, picked.size()), branchEntry);
					});
		}
		eligible.stream().map(Scored::option)
				.filter(o -> o.exit() && o.effects().stream().anyMatch(e -> "end".equals(e.type())))
				.filter(o -> !picked.contains(o)).findFirst().ifPresent(picked::add);
		// Defensive fallback for malformed third-party dialogue packs.
		for (Scored scored : eligible) {
			if (picked.size() >= PICK) break;
			if (!picked.contains(scored.option())) picked.add(scored.option());
		}
		return picked;
	}

	private static void pickTone(List<Scored> eligible, List<DialogueOption> picked, String tone) {
		for (Scored scored : eligible) {
			DialogueOption option = scored.option();
			if (!option.exit() && tone.equals(option.tone()) && !picked.contains(option)
					&& picked.stream().noneMatch(p -> p.category().equals(option.category()))) {
				picked.add(option);
				return;
			}
		}
		for (Scored scored : eligible) {
			DialogueOption option = scored.option();
			if (!option.exit() && tone.equals(option.tone()) && !picked.contains(option)) {
				picked.add(option);
				return;
			}
		}
	}

	private DialogueEngine() {
	}
}
