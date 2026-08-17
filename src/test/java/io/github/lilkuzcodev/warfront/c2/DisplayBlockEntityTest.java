package io.github.lilkuzcodev.warfront.c2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DisplayBlockEntityTest {
	@Test
	void firstRefreshDoesNotOverflowSentinel() {
		assertTrue(RefreshSchedule.due(100, Long.MIN_VALUE, 20));
	}

	@Test
	void refreshIntervalAndClockResetAreHandled() {
		assertFalse(RefreshSchedule.due(119, 100, 20));
		assertTrue(RefreshSchedule.due(120, 100, 20));
		assertTrue(RefreshSchedule.due(5, 100, 20));
	}
}
