package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.DeduplicationRecord;
import be.vdab.logguard.domain.model.ErrorFingerprint;
import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.out.DeduplicationRecordRepository;
import be.vdab.logguard.domain.port.out.LlmPort;
import be.vdab.logguard.domain.port.out.OpenSearchPort;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import be.vdab.logguard.domain.port.out.SuppressionFilePort;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 2.6 (degradation/recovery) + Story 4.2 (three-state dedup gate, LLM-analysis caching). Pure unit
 * tests over hand-written fakes (the project uses no mocking framework). The {@code FingerprintService}
 * is a real pure-domain instance; deduplication, terminal output, and LLM calls go through fakes that
 * count invocations so the gate's "analyse once per unique fingerprint" guarantee (NFR-4) is asserted.
 */
class PollServiceTest {

	private static final Instant CHECKPOINT_AT = Instant.parse("2026-06-01T00:00:00Z");
	private static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");
	private static final Duration REFRESH = Duration.ofSeconds(5);
	private static final Duration DEDUP_WINDOW = Duration.ofHours(24);
	private static final int MAX_FAILURES = 3;
	private static final List<Integer> ESCALATION_THRESHOLDS = List.of(10, 100, 1000);

	private final FakeOpenSearch openSearch = new FakeOpenSearch();
	private final FakeCheckpointRepository repository = new FakeCheckpointRepository();
	private final RecordingTerminal terminal = new RecordingTerminal();
	private final FakeLlm llm = new FakeLlm();
	private final FakeSuppression suppression = new FakeSuppression();
	private final FakeDeduplicationRepository dedup = new FakeDeduplicationRepository();
	private final FingerprintService fingerprintService = new FingerprintService(List.of("be.vdab"));

	private final PollService service = new PollService(
			openSearch, repository, terminal, llm, suppression, fingerprintService, dedup,
			DEDUP_WINDOW, REFRESH, MAX_FAILURES, ESCALATION_THRESHOLDS);

	// ---- Story 2.6: degradation / recovery (unchanged behaviour, must stay green) ----

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
		// Two DISTINCT own-code frames ⇒ distinct fingerprints ⇒ both new ⇒ both analysed (dedup does
		// not collapse them). Bare-type traces would share one fingerprint and collapse to a single call.
		openSearch.toReturn = List.of(
				errorIn("orgbeheer-service", "java.lang.NullPointerException\n\tat be.vdab.app.A.foo(A.java:1)"),
				errorIn("orgbeheer-service", "java.lang.NullPointerException\n\tat be.vdab.app.B.bar(B.java:2)"));

		service.poll();

		assertEquals(1, terminal.recoveryCalls, "recovery line prints once on the first successful poll");
		assertEquals(2, terminal.analysisCalls, "the full missed backlog is replayed");
		assertEquals(2, llm.analyseCalls, "two distinct fingerprints ⇒ two LLM calls");
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

	// ---- Story 4.2: three-state dedup gate ----

	@Test
	void newErrorCreatesRecordAnalysesCachesAndPrints() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, llm.analyseCalls, "a new error is analysed once");
		assertEquals(1, terminal.progressCalls);
		assertEquals(1, terminal.analysisCalls);
		DeduplicationRecord record = dedup.only();
		assertEquals(1, record.occurrenceCount());
		assertFalse(record.wontFix(), "suppression not integrated yet (Story 4.4) ⇒ wontFix=false");
		assertNull(record.lastNotifiedThreshold());
		assertNotNull(record.storedAnalysis(), "the analysis is cached on the record (FR-25)");
		assertTrue(record.expiresAt().isAfter(record.firstSeenAt()));
	}

	@Test
	void coolingDuplicateIncrementsSilentlyWithoutLlmOrOutput() {
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		ErrorFingerprint fingerprint = fingerprintService.compute(error);
		dedup.seedActive(DeduplicationRecord.createNew(fingerprint, NOW, DEDUP_WINDOW, false));
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(0, llm.analyseCalls, "cooling duplicate makes no LLM call");
		assertEquals(0, terminal.progressCalls, "no progress line for an all-duplicate service");
		assertEquals(0, terminal.analysisCalls, "duplicate produces no terminal block");
		assertEquals(2, dedup.byHash.get(fingerprint.hash()).occurrenceCount(), "count incremented");
	}

	@Test
	void duplicatesWithinOneBatchAnalysedOnce() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		openSearch.toReturn = List.of(error, error, error);

		service.poll();

		assertEquals(1, llm.analyseCalls, "three identical errors ⇒ one LLM call (NFR-4)");
		assertEquals(1, terminal.analysisCalls);
		assertEquals(3, dedup.only().occurrenceCount(), "all three counted");
	}

	@Test
	void twoDistinctFingerprintsOneDuplicateAnalysedTwice() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog a = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.A.foo(A.java:1)");
		ErrorLog b = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.B.bar(B.java:2)");
		openSearch.toReturn = List.of(a, b, a);

		service.poll();

		assertEquals(2, llm.analyseCalls, "two unique fingerprints ⇒ two LLM calls");
		assertEquals(2, terminal.analysisCalls);
	}

	@Test
	void sameFingerprintAcrossCyclesAnalysedOnlyOnce() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		openSearch.toReturn = List.of(error);

		service.poll();   // cycle 1: new ⇒ analysed + cached
		service.poll();   // cycle 2: same fingerprint now active ⇒ cooling, no fresh call (NFR-4 across cycles)

		assertEquals(1, llm.analyseCalls, "the cached fingerprint is not re-analysed on a later cycle");
		assertEquals(1, terminal.analysisCalls);
		assertEquals(2, dedup.only().occurrenceCount());
	}

	@Test
	void failedLlmAnalysisIsNotCached() {
		llm.result = LLMAnalysis.unavailable("connection refused");
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, llm.analyseCalls);
		assertEquals(1, terminal.analysisCalls, "the error is still displayed even when analysis failed (FR-24)");
		assertNull(dedup.only().storedAnalysis(), "a failed analysis must not be cached (would poison the window)");
	}

	@Test
	void allDuplicatesCyclePrintsNothingButAdvancesCheckpoint() {
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		ErrorFingerprint fingerprint = fingerprintService.compute(error);
		dedup.seedActive(DeduplicationRecord.createNew(fingerprint, NOW, DEDUP_WINDOW, false));
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error, error);

		service.poll();

		assertEquals(0, llm.analyseCalls);
		assertEquals(0, terminal.progressCalls);
		assertEquals(0, terminal.analysisCalls);
		assertNotNull(repository.saved, "an all-duplicate cycle is still a successful cycle");
		assertTrue(repository.saved.lastSuccessfulPollAt().isAfter(CHECKPOINT_AT), "checkpoint advances");
	}

	// ---- Story 4.4: won't-fix suppression + Fingerprint line ----

	@Test
	void wontFixFromBirth_suppressedNewError_noLlmNoBlock_printsLabelOnce_advancesCheckpoint() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		ErrorFingerprint fp = fingerprintService.compute(error);
		suppression.hashes = Set.of(fp.hash());
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(0, llm.analyseCalls, "won't-fix-from-birth makes no LLM call");
		assertEquals(0, terminal.progressCalls, "no service block for a suppressed error");
		assertEquals(0, terminal.analysisCalls, "not buffered for analysis/display");
		assertEquals(1, terminal.wontFixLabelCalls, "the ⚑ label prints exactly once");
		assertEquals(fp.humanLabel(), terminal.lastWontFixHumanLabel);
		assertEquals(fp.hash(), terminal.lastWontFixHash);
		DeduplicationRecord record = dedup.only();
		assertTrue(record.wontFix(), "record created with wontFix=true immediately");
		assertEquals(1, record.occurrenceCount());
		assertNull(record.storedAnalysis(), "no analysis cached");
		assertNotNull(repository.saved, "checkpoint still advances on a won't-fix-only cycle");
		assertTrue(repository.saved.lastSuccessfulPollAt().isAfter(CHECKPOINT_AT));
	}

	@Test
	void wontFixFromBirth_duplicateInSameBatch_labelOnce_countTwo() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		ErrorFingerprint fp = fingerprintService.compute(error);
		suppression.hashes = Set.of(fp.hash());
		openSearch.toReturn = List.of(error, error);

		service.poll();

		assertEquals(1, terminal.wontFixLabelCalls, "label at most once per window even with a same-batch duplicate");
		assertEquals(0, llm.analyseCalls);
		assertEquals(2, dedup.only().occurrenceCount(), "both occurrences counted (FR-10)");
	}

	@Test
	void activeWontFix_stillSuppressed_silentIncrementNoLabel() {
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		ErrorFingerprint fp = fingerprintService.compute(error);
		dedup.seedActive(DeduplicationRecord.createNew(fp, NOW, DEDUP_WINDOW, true));
		suppression.hashes = Set.of(fp.hash());
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(0, llm.analyseCalls);
		assertEquals(0, terminal.analysisCalls);
		assertEquals(0, terminal.wontFixLabelCalls, "an already-acknowledged won't-fix prints no new label");
		DeduplicationRecord record = dedup.byHash.get(fp.hash());
		assertTrue(record.wontFix(), "still won't-fix");
		assertEquals(2, record.occurrenceCount());
	}

	@Test
	void unsuppressionWithinWindow_clearsFlag_incrementsSilently_printsNothing() {
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		ErrorFingerprint fp = fingerprintService.compute(error);
		dedup.seedActive(DeduplicationRecord.createNew(fp, NOW, DEDUP_WINDOW, true));
		suppression.hashes = Set.of();   // hash removed from suppression.txt this cycle
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(0, llm.analyseCalls, "cooling ⇒ no fresh analysis (escalation is Story 4.5)");
		assertEquals(0, terminal.analysisCalls, "nothing printed on unsuppression");
		assertEquals(0, terminal.wontFixLabelCalls);
		DeduplicationRecord record = dedup.byHash.get(fp.hash());
		assertFalse(record.wontFix(), "wontFix cleared (FR-19)");
		assertEquals(2, record.occurrenceCount(), "occurrence still counted");
	}

	@Test
	void unsuppressionExpired_treatedAsBrandNewError() {
		// An expired record is invisible to findActiveByFingerprint (the Story 4.2 adapter filters it; the
		// fake models that invisibility as absence). With the hash also removed, the error is brand-new again
		// — no special code path (AC #5).
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		suppression.hashes = Set.of();
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, llm.analyseCalls, "expired + unsuppressed ⇒ analysed as a new error");
		assertEquals(1, terminal.analysisCalls);
		assertEquals(0, terminal.wontFixLabelCalls);
		assertFalse(dedup.only().wontFix(), "fresh record is not won't-fix");
	}

	@Test
	void newErrorBlock_carriesComputedFingerprintLine_fullSimpleName() {
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		ErrorLog error = errorIn("svc", "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)");
		ErrorFingerprint fp = fingerprintService.compute(error);
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, terminal.analysisCalls);
		assertEquals(fp.humanLabel(), terminal.lastAnalysisHumanLabel, "Fingerprint line uses the computed label");
		assertEquals(fp.hash(), terminal.lastAnalysisHash, "and the copy-pasteable hash");
		assertEquals("NullPointerException@Foo:7", fp.humanLabel(), "full simple exception name (METIS decision)");
	}

	// ---- Story 4.5: escalation threshold re-notifications + won't-fix volume override ----

	private static final String NPE_OWN_FRAME =
			"java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)";
	private static final LLMAnalysis CACHED = LLMAnalysis.available("npe on null label", "Foo.bar:7", "guard it");

	/** Seed an active record with an explicit occurrence count / lastNotified / wontFix (createNew only gives count=1). */
	private DeduplicationRecord seed(ErrorFingerprint fp, int count, Integer lastNotified, boolean wontFix,
									 LLMAnalysis analysis) {
		DeduplicationRecord record = new DeduplicationRecord(
				fp, NOW, NOW.plus(DEDUP_WINDOW), count, lastNotified, wontFix, analysis);
		dedup.seedActive(record);
		return record;
	}

	@Test
	void coolingCrossing10xFiresEscalationReusingStoredAnalysisNoLlm() {
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		seed(fp, 9, null, false, CACHED);
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, terminal.escalationCalls, "crossing 10× fires one escalation");
		assertEquals(10, terminal.lastEscalationThreshold);
		assertFalse(terminal.lastEscalationWontFix, "cooling shape");
		assertEquals(fp.humanLabel(), terminal.lastEscalationHumanLabel);
		assertEquals(CACHED, terminal.lastEscalationStored, "stored analysis reused — no fresh call");
		assertEquals(0, llm.analyseCalls, "escalation makes no LLM call (FR-25)");
		assertEquals(0, terminal.analysisCalls, "cooling escalation is not a new-error block");
		DeduplicationRecord after = dedup.byHash.get(fp.hash());
		assertEquals(10, after.occurrenceCount());
		assertEquals(10, after.lastNotifiedThreshold());
	}

	@Test
	void coolingBelowNextThresholdDoesNotRefire() {
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		seed(fp, 10, 10, false, CACHED);   // 10× already fired
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(0, terminal.escalationCalls, "11 < 100 ⇒ no threshold re-fires");
		assertEquals(11, dedup.byHash.get(fp.hash()).occurrenceCount());
		assertEquals(10, dedup.byHash.get(fp.hash()).lastNotifiedThreshold(), "unchanged");
	}

	@Test
	void coolingCrossing100xAfter10xAlreadyFired() {
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		seed(fp, 99, 10, false, CACHED);
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, terminal.escalationCalls);
		assertEquals(100, terminal.lastEscalationThreshold, "the next ladder rung fires");
		assertEquals(100, dedup.byHash.get(fp.hash()).lastNotifiedThreshold());
	}

	@Test
	void coolingEscalationWithoutStoredAnalysisPassesNull() {
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		seed(fp, 9, null, false, null);   // a failed initial analysis was never cached (FR-25)
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, terminal.escalationCalls);
		assertNull(terminal.lastEscalationStored, "adapter renders this as \"no analysis on file\"");
	}

	@Test
	void wontFixReaching1000xFiresVolumeOverrideAndKeepsFlag() {
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		seed(fp, 999, 100, true, null);
		suppression.hashes = Set.of(fp.hash());   // still suppressed
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, terminal.escalationCalls, "the 1000× override fires");
		assertEquals(1000, terminal.lastEscalationThreshold);
		assertTrue(terminal.lastEscalationWontFix, "won't-fix override shape");
		assertEquals(0, llm.analyseCalls);
		DeduplicationRecord after = dedup.byHash.get(fp.hash());
		assertTrue(after.wontFix(), "override is a read-only nudge — wontFix NOT cleared (FR-12)");
		assertEquals(1000, after.occurrenceCount());
		assertEquals(1000, after.lastNotifiedThreshold());
	}

	@Test
	void wontFixCrossing10xDoesNotPrintButAdvancesThreshold() {
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		seed(fp, 9, null, true, null);
		suppression.hashes = Set.of(fp.hash());
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(0, terminal.escalationCalls, "won't-fix fires only at 1000× — 10× is silent (FR-12)");
		DeduplicationRecord after = dedup.byHash.get(fp.hash());
		assertEquals(10, after.lastNotifiedThreshold(), "but the threshold still advances silently");
		assertTrue(after.wontFix());
	}

	@Test
	void coolingCountJumpedManyRungsFiresHighestReachedMilestone() {
		// Count persisted ahead of lastNotifiedThreshold (e.g. escalation-thresholds reconfigured between
		// restarts): the next increment must report the TRUE milestone (1000×), not a misleading lower rung.
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		seed(fp, 999, null, false, CACHED);
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, terminal.escalationCalls, "fires once");
		assertEquals(1000, terminal.lastEscalationThreshold, "reports the highest crossed rung, not 10×");
		assertEquals(1000, dedup.byHash.get(fp.hash()).lastNotifiedThreshold(),
				"lastNotifiedThreshold advances to the fired rung — lower rungs are subsumed, not deferred");
	}

	@Test
	void wontFixCountJumpedToOverrideFiresImmediately() {
		// A won't-fix count jumping straight past 1000 must fire the override now, not defer it behind 10×/100×.
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		seed(fp, 999, null, true, null);
		suppression.hashes = Set.of(fp.hash());
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error);

		service.poll();

		assertEquals(1, terminal.escalationCalls, "the 1000× override fires on the jump");
		assertEquals(1000, terminal.lastEscalationThreshold);
		assertTrue(terminal.lastEscalationWontFix);
		assertTrue(dedup.byHash.get(fp.hash()).wontFix(), "still a read-only nudge");
	}

	@Test
	void escalationFiresInSequenceWhenCountIsReconstructedWithinOneBatch() {
		// 10 occurrences of a brand-new fingerprint in one batch (e.g. backlog catchup): the 1st is new
		// (analysed once), occurrences 2-10 increment until the 10th crosses 10× — fires once, in sequence.
		ErrorLog error = errorIn("svc", NPE_OWN_FRAME);
		ErrorFingerprint fp = fingerprintService.compute(error);
		repository.stored = Optional.of(new PollCheckpoint(CHECKPOINT_AT, null, 0));
		openSearch.toReturn = List.of(error, error, error, error, error, error, error, error, error, error);

		service.poll();

		assertEquals(1, llm.analyseCalls, "the new fingerprint is analysed once (NFR-4)");
		assertEquals(1, terminal.analysisCalls);
		assertEquals(1, terminal.escalationCalls, "crossing 10× during catchup fires once");
		assertEquals(10, terminal.lastEscalationThreshold);
		assertEquals(10, dedup.only().occurrenceCount());
	}

	private static ErrorLog errorIn(String service) {
		return errorIn(service, "java.lang.NullPointerException");
	}

	private static ErrorLog errorIn(String service, String stackTrace) {
		return new ErrorLog("java.lang.NullPointerException", "boom", stackTrace,
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
		int wontFixLabelCalls;
		int escalationCalls;
		int lastDegradedFailures;
		String lastAnalysisHumanLabel;
		String lastAnalysisHash;
		String lastWontFixHumanLabel;
		String lastWontFixHash;
		int lastEscalationThreshold;
		boolean lastEscalationWontFix;
		String lastEscalationHumanLabel;
		LLMAnalysis lastEscalationStored;

		@Override
		public void printProgress(String serviceName, int count) {
			progressCalls++;
		}

		@Override
		public void printAnalysis(int index, int total, ErrorLog error, LLMAnalysis analysis,
								  String humanLabel, String hash) {
			analysisCalls++;
			lastAnalysisHumanLabel = humanLabel;
			lastAnalysisHash = hash;
		}

		@Override
		public void printWontFixLabel(String humanLabel, String hash) {
			wontFixLabelCalls++;
			lastWontFixHumanLabel = humanLabel;
			lastWontFixHash = hash;
		}

		@Override
		public void printEscalation(String humanLabel, int threshold, Instant firstSeen, LLMAnalysis stored,
									boolean wontFix) {
			escalationCalls++;
			lastEscalationThreshold = threshold;
			lastEscalationWontFix = wontFix;
			lastEscalationHumanLabel = humanLabel;
			lastEscalationStored = stored;
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

		@Override
		public void printSuppressionUnreadable() {
			// not exercised by these tests
		}
	}

	private static final class FakeLlm implements LlmPort {
		int analyseCalls;
		LLMAnalysis result = LLMAnalysis.available("root", "Foo.bar:7", "fix it");

		@Override
		public LLMAnalysis analyse(ErrorLog error) {
			analyseCalls++;
			return result;
		}
	}

	private static final class FakeSuppression implements SuppressionFilePort {
		Set<String> hashes = Set.of();

		@Override
		public Set<String> loadHashes() {
			return hashes;
		}
	}

	/** In-memory dedup store keyed by fingerprint hash; expiry filtering is the real adapter's job. */
	private static final class FakeDeduplicationRepository implements DeduplicationRecordRepository {
		final Map<String, DeduplicationRecord> byHash = new HashMap<>();

		void seedActive(DeduplicationRecord record) {
			byHash.put(record.fingerprint().hash(), record);
		}

		DeduplicationRecord only() {
			assertEquals(1, byHash.size(), "expected exactly one dedup record");
			return byHash.values().iterator().next();
		}

		@Override
		public Optional<DeduplicationRecord> findActiveByFingerprint(ErrorFingerprint fingerprint) {
			return Optional.ofNullable(byHash.get(fingerprint.hash()));
		}

		@Override
		public void save(DeduplicationRecord record) {
			byHash.put(record.fingerprint().hash(), record);
		}

		@Override
		public void deleteExpired(Instant before) {
			byHash.values().removeIf(record -> !record.expiresAt().isAfter(before));
		}
	}
}
