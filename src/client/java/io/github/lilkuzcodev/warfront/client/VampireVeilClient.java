package io.github.lilkuzcodev.warfront.client;

/**
 * Client half of the vampire's veil (see systems/VampireVeil). While active, three
 * client-only render hooks turn this player's world to midnight under a blood-red full
 * moon with snow falling — the server's real time and weather are untouched, so leaving
 * the castle (or logging out) simply drops the flag and the honest sky returns.
 */
public final class VampireVeilClient {

	private static volatile boolean active;

	private VampireVeilClient() {}

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}
}
