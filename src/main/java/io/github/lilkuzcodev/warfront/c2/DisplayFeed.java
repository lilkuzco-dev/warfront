package io.github.lilkuzcodev.warfront.c2;

/** The feeds understood by both wall screens and table projectors. */
public enum DisplayFeed {
	LIVE_MAP("live_map"),
	SATELLITE("satellite"),
	TACTICAL("tactical");

	private final String id;

	DisplayFeed(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static DisplayFeed byId(String id) {
		for (DisplayFeed feed : values()) {
			if (feed.id.equals(id)) {
				return feed;
			}
		}
		return LIVE_MAP;
	}

	public DisplayFeed next(boolean satelliteAvailable) {
		return switch (this) {
			case LIVE_MAP -> satelliteAvailable ? SATELLITE : TACTICAL;
			case SATELLITE -> TACTICAL;
			case TACTICAL -> LIVE_MAP;
		};
	}
}
