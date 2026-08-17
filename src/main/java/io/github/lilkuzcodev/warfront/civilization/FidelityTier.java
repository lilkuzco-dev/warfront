package io.github.lilkuzcodev.warfront.civilization;

/** Exactly one representation owns a citizen at a time. */
public enum FidelityTier {
	EMBODIED("embodied"),
	LOCAL_ABSTRACT("local"),
	VIRTUAL("virtual");

	private final String id;

	FidelityTier(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static FidelityTier byId(String id) {
		for (FidelityTier tier : values()) {
			if (tier.id.equals(id)) return tier;
		}
		return VIRTUAL;
	}
}
