package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.in.PollUseCase;
import be.vdab.logguard.domain.port.out.OpenSearchPort;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One poll cycle (minimal Epic-2 core): fetch errors since the checkpoint, print them grouped by
 * service (most-affected first), then advance the checkpoint. Pure domain — zero Spring annotations
 * (wired as a bean in {@code infrastructure/config}).
 *
 * <p>NOT yet implemented (their own stories): deduplication/fingerprinting (Epic 4), LLM analysis
 * (Epic 3 — errors show "analysis unavailable"), degradation detection (Story 2.6), suppression-file
 * reload (Story 4.3), and the transactional checkpoint-advance boundary (Story 2.5).</p>
 */
public class PollService implements PollUseCase {

    private final OpenSearchPort openSearchPort;
    private final PollCheckpointRepository checkpointRepository;
    private final TerminalOutputPort terminalOutput;

    public PollService(OpenSearchPort openSearchPort,
                       PollCheckpointRepository checkpointRepository,
                       TerminalOutputPort terminalOutput) {
        this.openSearchPort = openSearchPort;
        this.checkpointRepository = checkpointRepository;
        this.terminalOutput = terminalOutput;
    }

    @Override
    public void poll() {
        Optional<PollCheckpoint> current = checkpointRepository.load();
        if (current.isEmpty()) {
            // First-run prompt has not completed yet (ApplicationRunner runs after scheduling starts).
            return;
        }
        PollCheckpoint checkpoint = current.get();

        // Snapshot the poll start BEFORE querying; the checkpoint advances to here so errors arriving
        // mid-cycle are picked up next time rather than skipped.
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
                    .forEach(entry -> terminalOutput.printServiceErrors(entry.getKey(), entry.getValue()));
        }
        // FR-26: zero errors -> print nothing (handled above). Advance the checkpoint only after a
        // successful query + delivery; if findErrorsSince threw, we never reach here (checkpoint stasis).
        checkpointRepository.save(new PollCheckpoint(
                pollStart, checkpoint.degradationStartedAt(), checkpoint.consecutivePollFailures()));
    }
}
