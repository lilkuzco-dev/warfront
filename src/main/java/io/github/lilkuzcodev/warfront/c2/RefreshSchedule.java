package io.github.lilkuzcodev.warfront.c2;

/** Overflow-safe tick cadence shared by server and client display refreshes. */
final class RefreshSchedule {
	static boolean due(long gameTime, long lastRefresh, long interval) {
		return lastRefresh == Long.MIN_VALUE || gameTime < lastRefresh || gameTime - lastRefresh >= interval;
	}

	private RefreshSchedule() { }
}
