package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.out.LlmPort;
import be.vdab.logguard.domain.port.out.OpenSearchPort;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import be.vdab.logguard.domain.port.out.SuppressionFilePort;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 2.6: degradation detection, sticky banner, and recovery. Pure unit tests over hand-written fakes
 * (the project does not use a mocking framework). Verifies the OpenSearch-outage failure count, checkpoint
 * stasis, threshold entry into DegradedState, sticky banner reprint, and recovery with backlog replay.
 */
class PollServiceTest {

	private static final Instant CHECKPOINT_AT = Instant.parse("2026-06-01T00:00:00Z");
	private static final Duration REFRESH = Duration.ofSeconds(5);
	private static final int MAX_FAILURES = 3;

	private final FakeOpenSearch openSearch = new FakeOpenSearch();
	private final FakeCheckpointRepository repository = new FakeCheckpointRepository();
	private final RecordingTerminal terminal = new RecordingTerminal();
	private final FakeLlm llm = new FakeLlm();
	private final FakeSuppression suppression = new FakeSuppression();

	private final PollService service = new PollService(
			openSearch, repository, terminal, llm, suppression, REFRESH, MAX_FAILURES);

	@Test
	void emptyCheckpointSkipsCycle() {
		repository.stored = Optional.empty();

		service.poll();

		assertNull(repository.saved, "no checkpoint should be saved before first-run init completes");
		assertEquals(0, terminal.degradedCalls);
		assertEquals(0, terminal.recoveryCalls);
	}

	@Test
	void successfulHealthyPollAdvancesAndClearsState() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of();

		service.poll();

		assertNotNull(repository.saved);
		assertTrue(repository.saved.lastSuccessfulPollAt().isAfter(CHECKPOINT_AT), "checkpoint should advance");
		assertNull(repository.saved.degradationStartedAt());
		assertEquals(0, repository.saved.consecutivePollFailures());
		assertEquals(0, terminal.degradedCalls);
		assertEquals(0, terminal.recoveryCalls);
	}

	@Test
	void firstFailureBelowThresholdIncrementsAndHoldsCheckpoint() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.failWith = new RuntimeException("connection refused");

		service.poll();

		assertEquals(CHECKPOINT_AT, repository.saved.lastSuccessfulPollAt(), "checkpoint must not advance on failure");
		assertEquals(1, repository.saved.consecutivePollFailures());
		assertNull(repository.saved.degradationStartedAt(), "below threshold ⇒ not degraded yet");
		assertEquals(0, terminal.degradedCalls, "no banner below threshold");
	}

	@Test
	void failureCrossingThresholdEntersDegradedAndPrintsBanner() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, MAX_FAILURES - 1));
		openSearch.failWith = new RuntimeException("timeout");

		service.poll();

		assertEquals(MAX_FAILURES, repository.saved.consecutivePollFailures());
		assertNotNull(repository.saved.degradationStartedAt(), "threshold reached ⇒ degraded");
		assertEquals(1, terminal.degradedCalls);
		assertEquals(MAX_FAILURES, terminal.lastDegradedFailures);
	}

	@Test
	void failureWhileDegradedKeepsOriginalTimestampAndReprints() {
		Instant degradedSince = Instant.parse("2026-06-02T00:00:00Z");
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, degradedSince, MAX_FAILURES));
		openSearch.failWith = new RuntimeException("still down");

		service.poll();

		assertEquals(degradedSince, repository.saved.degradationStartedAt(), "degradation start is sticky");
		assertEquals(MAX_FAILURES + 1, repository.saved.consecutivePollFailures());
		assertEquals(1, terminal.degradedCalls, "banner reprints each degraded cycle");
		assertEquals(MAX_FAILURES + 1, terminal.lastDegradedFailures);
	}

	@Test
	void recoveryPrintsBannerReplaysBacklogAndClearsState() {
		Instant degradedSince = Instant.parse("2026-06-02T00:00:00Z");
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, degradedSince, 5));
		openSearch.toReturn = List.of(errorIn("orgbeheer-service"), errorIn("orgbeheer-service"));

		service.poll();

		assertEquals(1, terminal.recoveryCalls, "recovery line prints once on the first successful poll");
		assertEquals(2, terminal.analysisCalls, "the full missed backlog is replayed");
		assertNull(repository.saved.degradationStartedAt(), "degradation cleared on recovery");
		assertEquals(0, repository.saved.consecutivePollFailures());
		assertTrue(repository.saved.lastSuccessfulPollAt().isAfter(CHECKPOINT_AT));
	}

	@Test
	void belowThresholdFailuresThenSuccessResetsWithoutBanner() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, MAX_FAILURES - 1));
		openSearch.toReturn = List.of();

		service.poll();

		assertEquals(0, repository.saved.consecutivePollFailures(), "success resets the failure count");
		assertNull(repository.saved.degradationStartedAt());
		assertEquals(0, terminal.recoveryCalls, "no recovery banner when never degraded");
		assertFalse(repository.saved.lastSuccessfulPollAt().isBefore(CHECKPOINT_AT));
	}

	private static ErrorLog errorIn(String service) {
		return new ErrorLog("java.lang.NullPointerException", "boom", "java.lang.NullPointerException",
				service, "app", "team", "local", Instant.parse("2026-06-15T00:00:00Z"),
				"cn=X,ou=Y");
	}

	// ---- fakes ----

	private static final class FakeOpenSearch implements OpenSearchPort {
		List<ErrorLog> toReturn = List.of();
		RuntimeException failWith;

		@Override
		public List<ErrorLog> findErrorsSince(Instant from) {
			if (failWith != null) {
				throw failWith;
			}
			return toReturn;
		}
	}

	private static final class FakeCheckpointRepository implements PollCheckpointRepository {
		Optional<PollCheckpoint> stored = Optional.empty();
		PollCheckpoint saved;

		@Override
		public Optional<PollCheckpoint> load() {
			return stored;
		}

		@Override
		public void save(PollCheckpoint checkpoint) {
			this.saved = checkpoint;
		}
	}

	private static final class RecordingTerminal implements TerminalOutputPort {
		int progressCalls;
		int analysisCalls;
		int degradedCalls;
		int recoveryCalls;
		int lastDegradedFailures;

		@Override
		public void printProgress(String serviceName, int count) {
			progressCalls++;
		}

		@Override
		public void printAnalysis(int index, int total, ErrorLog error, LLMAnalysis analysis) {
			analysisCalls++;
		}

		@Override
		public void printDegraded(Instant degradationStartedAt, int consecutiveFailures) {
			degradedCalls++;
			lastDegradedFailures = consecutiveFailures;
		}

		@Override
		public void printRecovery(Instant resumedAt) {
			recoveryCalls++;
		}
	}

	private static final class FakeLlm implements LlmPort {
		@Override
		public LLMAnalysis analyse(ErrorLog error) {
			return LLMAnalysis.unavailable("test");
		}
	}

	private static final class FakeSuppression implements SuppressionFilePort {
		@Override
		public Set<String> loadHashes() {
			return Set.of();
		}
	}
}
