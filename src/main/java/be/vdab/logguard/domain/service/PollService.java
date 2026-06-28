package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.in.PollUseCase;
import be.vdab.logguard.domain.port.out.LlmPort;
import be.vdab.logguard.domain.port.out.OpenSearchPort;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import be.vdab.logguard.domain.port.out.SuppressionFilePort;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;

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
 * <p>NOT yet fully implemented (their own stories): deduplication/fingerprinting (Epic 4 — without it every
 * error triggers an LLM call, vs NFR-4's dedup-before-LLM), escalation, degradation detection (Story 2.6),
 * and real suppression-file parsing/hot-reload (Story 4.3 — the port is reloaded each cycle here but the
 * stub returns an empty set, so nothing is suppressed yet).</p>
 */
public class PollService implements PollUseCase {

    private final OpenSearchPort openSearchPort;
    private final PollCheckpointRepository checkpointRepository;
    private final TerminalOutputPort terminalOutput;
    private final LlmPort llmPort;
    private final SuppressionFilePort suppressionFilePort;
    private final Duration refreshWindow;

    public PollService(OpenSearchPort openSearchPort,
                       PollCheckpointRepository checkpointRepository,
                       TerminalOutputPort terminalOutput,
                       LlmPort llmPort,
                       SuppressionFilePort suppressionFilePort,
                       Duration refreshWindow) {
        this.openSearchPort = openSearchPort;
        this.checkpointRepository = checkpointRepository;
        this.terminalOutput = terminalOutput;
        this.llmPort = llmPort;
        this.suppressionFilePort = suppressionFilePort;
        this.refreshWindow = refreshWindow;
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
        List<ErrorLog> errors = openSearchPort.findErrorsSince(checkpoint.lastSuccessfulPollAt());

        if (!errors.isEmpty()) {
            Map<String, List<ErrorLog>> byService = errors.stream()
                    .collect(Collectors.groupingBy(
                            error -> error.serviceName() != null ? error.serviceName() : "unknown",
                            LinkedHashMap::new,
                            Collectors.toList()));
            // FR-29: most-affected service first.
            byService.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, List<ErrorLog>> e) -> e.getValue().size())
                            .reversed())
                    .forEach(entry -> printServiceGroup(entry.getKey(), entry.getValue()));
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
        checkpointRepository.save(new PollCheckpoint(
                advanced, checkpoint.degradationStartedAt(), checkpoint.consecutivePollFailures()));
    }

    private void printServiceGroup(String serviceName, List<ErrorLog> errors) {
        terminalOutput.printProgress(serviceName, errors.size());
        int index = 1;
        for (ErrorLog error : errors) {
            LLMAnalysis analysis = llmPort.analyse(error);
            terminalOutput.printAnalysis(index++, errors.size(), error, analysis);
        }
    }
}
