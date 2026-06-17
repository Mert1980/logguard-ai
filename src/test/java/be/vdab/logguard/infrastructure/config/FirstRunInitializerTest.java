package be.vdab.logguard.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit tests for the first-run lookback parsing/offset logic (FR-2). No Spring context.
 */
class FirstRunInitializerTest {

	private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

	@Test
	void positiveNumberGivesNowMinusThatManyHours() {
		assertEquals(NOW.minus(Duration.ofHours(6)), FirstRunInitializer.computeCheckpoint("6", NOW));
	}

	@Test
	void zeroGivesNow() {
		assertEquals(NOW, FirstRunInitializer.computeCheckpoint("0", NOW));
	}

	@Test
	void blankOrNullGivesDefault24Hours() {
		assertEquals(NOW.minus(Duration.ofHours(24)), FirstRunInitializer.computeCheckpoint("", NOW));
		assertEquals(NOW.minus(Duration.ofHours(24)), FirstRunInitializer.computeCheckpoint("   ", NOW));
		assertEquals(NOW.minus(Duration.ofHours(24)), FirstRunInitializer.computeCheckpoint(null, NOW));
	}

	@Test
	void invalidOrNegativeGivesDefault24Hours() {
		assertEquals(NOW.minus(Duration.ofHours(24)), FirstRunInitializer.computeCheckpoint("abc", NOW));
		assertEquals(NOW.minus(Duration.ofHours(24)), FirstRunInitializer.computeCheckpoint("-3", NOW));
	}

	@Test
	void whitespacePaddedNumberIsParsed() {
		assertEquals(NOW.minus(Duration.ofHours(6)), FirstRunInitializer.computeCheckpoint(" 6 ", NOW));
	}
}
