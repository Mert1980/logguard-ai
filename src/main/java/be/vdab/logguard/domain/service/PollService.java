package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.DeduplicationRecord;
import be.vdab.logguard.domain.model.ErrorFingerprint;
import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.in.PollUseCase;
import be.vdab.logguard.domain.port.out.DeduplicationRecordRepository;
import be.vdab.logguard.domain.port.out.LlmPort;
import be.vdab.logguard.domain.port.out.OpenSearchPort;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import be.vdab.logguard.domain.port.out.SuppressionFilePort;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One poll cycle (minimal Epic-2/3 core): fetch errors since the checkpoint, analyse each with the LLM,
 * print grouped by service (most-affected first), then advance the checkpoint. Pure domain — zero Spring
 * annotations (wired as a bean in {@code infrastructure/config}).
 *
 * <p>The transactional checkpoint-advance boundary (AR-9 / NFR-5) is applied by the
 * {@code TransactionalPollUseCase} wrapper in {@code infrastructure/config} — this class stays Spring-free.</p>
 *
 * <p>Degradation detection / sticky banner / recovery with auto-backlog is implemented here (Story 2.6):
 * an OpenSearch outage increments the failure count and holds the checkpoint, the banner reprints each
 * cycle past the threshold, and the first successful poll clears the state and replays the missed backlog.</p>
 *
 * <p>Deduplication gate (Story 4.2): each error is fingerprinted; a fingerprint with no active
 * {@link DeduplicationRecord} is new (analysed once, cached, displayed), while an active one only
 * increments the occurrence count — silently. This bounds LLM calls to unique fingerprints per cycle
 * (NFR-4). NOT yet wired (their own stories): suppression-file consumption / won't-fix (Story 4.3/4.4 —
 * the port is reloaded each cycle here for the FR-14 ordering, but its result is not consulted yet, so
 * records are always created with {@code wontFix=false}), and escalation re-notifications (Story 4.5).</p>
 */
public class PollService implements PollUseCase {

    private static final Logger log = LoggerFactory.getLogger(PollService.class);

    private final OpenSearchPort openSearchPort;
    private final PollCheckpointRepository checkpointRepository;
    private final TerminalOutputPort terminalOutput;
    private final LlmPort llmPort;
    private final SuppressionFilePort suppressionFilePort;
    private final FingerprintService fingerprintService;
    private final DeduplicationRecordRepository dedupRepository;
    private final Duration deduplicationWindow;
    private final Duration refreshWindow;
    private final int maxConsecutivePollFailures;

    public PollService(OpenSearchPort openSearchPort,
                       PollCheckpointRepository checkpointRepository,
                       TerminalOutputPort terminalOutput,
                       LlmPort llmPort,
                       SuppressionFilePort suppressionFilePort,
                       FingerprintService fingerprintService,
                       DeduplicationRecordRepository dedupRepository,
                       Duration deduplicationWindow,
                       Duration refreshWindow,
                       int maxConsecutivePollFailures) {
        this.openSearchPort = openSearchPort;
        this.checkpointRepository = checkpointRepository;
        this.terminalOutput = terminalOutput;
        this.llmPort = llmPort;
        this.suppressionFilePort = suppressionFilePort;
        this.fingerprintService = fingerprintService;
        this.dedupRepository = dedupRepository;
        this.deduplicationWindow = deduplicationWindow;
        this.refreshWindow = refreshWindow;
        this.maxConsecutivePollFailures = maxConsecutivePollFailures;
    }

    @Override
    public void poll() {
        Optional<PollCheckpoint> current = checkpointRepository.load();
        if (current.isEmpty()) {
            // First-run prompt has not completed yet (ApplicationRunner runs after scheduling starts).
            return;
        }
        PollCheckpoint checkpoint = current.get();

        // FR-14: reload the suppression list FIRST, before any error in this batch is processed, so a
        // hot-edited file takes effect within one cycle. Stub returns empty today; Epic 4's dedup gate
        // will consume the result. The call's ordering is the contract (Story 4.3 also surfaces an
        // "unreadable → last known state" warning here).
        suppressionFilePort.loadHashes();

        // Snapshot the poll start BEFORE querying; the checkpoint advances to here (minus a refresh
        // safety-lag) so errors arriving mid-cycle are picked up next time rather than skipped.
        Instant pollStart = Instant.now();
        List<ErrorLog> errors;
        try {
            errors = openSearchPort.findErrorsSince(checkpoint.lastSuccessfulPollAt());
        } catch (Exception e) {
            // FR-33/34: OpenSearch unreachable. Count the failure, hold the checkpoint (stasis — no
            // advance), and once the threshold is crossed flag degradation and reprint the sticky banner
            // each cycle. We do NOT rethrow, so the transaction commits this failure state.
            handlePollFailure(checkpoint, e);
            return;
        }

        // FR-35: a successful query after degradation is the recovery point. Announce it before the
        // backlog prints; the checkpoint never advanced while degraded, so `errors` IS the full backlog.
        if (checkpoint.degradationStartedAt() != null) {
            terminalOutput.printRecovery(Instant.now());
        }

        // FR-8: three-state dedup gate, two-phase so the progress line counts only errors that will
        // actually be analysed/shown and an all-duplicate service prints nothing.
        List<ErrorLog> newErrors = gateAndCollectNew(errors);
        if (!newErrors.isEmpty()) {
            Map<String, List<ErrorLog>> byService = newErrors.stream()
                    .collect(Collectors.groupingBy(
                            error -> error.serviceName() != null ? error.serviceName() : "unknown",
                            LinkedHashMap::new,
                            Collectors.toList()));
            // FR-29: most-affected service first (over deduplicated counts).
            byService.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, List<ErrorLog>> e) -> e.getValue().size())
                            .reversed())
                    .forEach(entry -> analyseServiceGroup(entry.getKey(), entry.getValue()));
        }
        // FR-26: zero errors -> print nothing. Advance the checkpoint only after a successful query +
        // delivery; if findErrorsSince threw, we never reach here (checkpoint stasis).
        // D1: subtract a refresh safety-lag so an error made searchable just after this query ran
        // (OpenSearch refresh latency) is re-queried next cycle instead of skipped. Clamp so the
        // checkpoint never regresses behind its previous value.
        Instant advanced = pollStart.minus(refreshWindow);
        if (advanced.isBefore(checkpoint.lastSuccessfulPollAt())) {
            advanced = checkpoint.lastSuccessfulPollAt();
        }
        // FR-35: a successful cycle is healthy — clear degradation state and reset the failure count.
        checkpointRepository.save(new PollCheckpoint(advanced, null, 0));
    }

    /**
     * FR-33/34: record a failed poll cycle. Increments the consecutive-failure count, holds the
     * checkpoint (no advance), enters DegradedState once {@code maxConsecutivePollFailures} is reached,
     * and reprints the sticky banner every cycle while degraded. Called instead of advancing on an
     * OpenSearch outage; never rethrows, so the surrounding transaction commits the failure state.
     */
    private void handlePollFailure(PollCheckpoint checkpoint, Exception cause) {
        int failures = checkpoint.consecutivePollFailures() + 1;
        log.warn("Poll cycle failed ({} consecutive): {}", failures, cause.getMessage());
        Instant degradationStartedAt = checkpoint.degradationStartedAt();
        if (degradationStartedAt == null && failures >= maxConsecutivePollFailures) {
            degradationStartedAt = Instant.now();
        }
        checkpointRepository.save(new PollCheckpoint(
                checkpoint.lastSuccessfulPollAt(), degradationStartedAt, failures));
        if (degradationStartedAt != null) {
            terminalOutput.printDegraded(degradationStartedAt, failures);
        }
    }

    /**
     * Phase 1 of the dedup gate: fingerprint every error and update its {@link DeduplicationRecord}.
     * An active record (cooling) only has its occurrence count incremented — silent, no LLM, no output
     * (FR-8). A fingerprint with no active record is new: create the record now (so a duplicate later in
     * the SAME batch is found active and increments instead of being analysed twice — NFR-4) and return
     * its first occurrence for analysis in phase 2. Suppression is not consulted yet (Story 4.4), so new
     * records are always created with {@code wontFix=false}.
     */
    private List<ErrorLog> gateAndCollectNew(List<ErrorLog> errors) {
        Instant now = Instant.now();
        // Preserve first-seen order of new fingerprints; one entry per fingerprint hash.
        Map<String, ErrorLog> newByHash = new LinkedHashMap<>();
        for (ErrorLog error : errors) {
            ErrorFingerprint fingerprint = fingerprintService.compute(error);
            Optional<DeduplicationRecord> active = dedupRepository.findActiveByFingerprint(fingerprint);
            if (active.isPresent()) {
                // Cooling (or a duplicate already created earlier this batch): increment silently.
                dedupRepository.save(active.get().incrementOccurrence());
            } else if (!newByHash.containsKey(fingerprint.hash())) {
                // New fingerprint, first occurrence: persist count=1 before phase-2 analysis.
                dedupRepository.save(DeduplicationRecord.createNew(fingerprint, now, deduplicationWindow, false));
                newByHash.put(fingerprint.hash(), error);
            }
        }
        return List.copyOf(newByHash.values());
    }

    /**
     * Phase 2: analyse and display the new errors of one service (FR-27/28/30). Re-fetches the record
     * before caching so the stored analysis is written onto the current occurrence count (which phase 1
     * may have bumped past 1) rather than clobbering it (FR-25).
     */
    private void analyseServiceGroup(String serviceName, List<ErrorLog> errors) {
        terminalOutput.printProgress(serviceName, errors.size());
        int index = 1;
        for (ErrorLog error : errors) {
            ErrorFingerprint fingerprint = fingerprintService.compute(error);
            LLMAnalysis analysis = llmPort.analyse(error);
            // FR-25: cache only a SUCCESSFUL analysis. Caching a transient failure (Ollama down/timeout)
            // would poison the dedup window — every later occurrence is suppressed and escalation
            // (Story 4.5) would replay the failure instead of a real root cause. A failed analysis is
            // still displayed below (FR-24); storedAnalysis just stays null so it can be retried.
            if (analysis.llmAvailable()) {
                Optional<DeduplicationRecord> current = dedupRepository.findActiveByFingerprint(fingerprint);
                if (current.isPresent()) {
                    dedupRepository.save(current.get().withStoredAnalysis(analysis));
                } else {
                    // Phase 1 created this record earlier in the same cycle; absence here is unexpected.
                    log.warn("Dedup record for fingerprint {} not found when caching its analysis", fingerprint.hash());
                }
            }
            terminalOutput.printAnalysis(index++, errors.size(), error, analysis);
        }
    }
}
