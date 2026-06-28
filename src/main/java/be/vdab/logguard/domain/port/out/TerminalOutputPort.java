package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;

import java.time.Instant;

/**
 * Outbound port for user-facing terminal output. The only implementation is allowed to touch
 * {@code System.out} (AR-10). No Spring annotations here.
 */
public interface TerminalOutputPort {

    /** FR-27/28: "Analyzing N new errors in [service]..." + the service block header, before analysis. */
    void printProgress(String serviceName, int count);

    /** FR-30: one per-error block, showing the LLM analysis (or "analysis unavailable (reason)", FR-24). */
    void printAnalysis(int index, int total, ErrorLog error, LLMAnalysis analysis);

    /** FR-34: sticky degradation banner, reprinted every cycle OpenSearch stays unreachable (Story 2.6). */
    void printDegraded(Instant degradationStartedAt, int consecutiveFailures);

    /** FR-35: one-time recovery line when OpenSearch becomes reachable again (Story 2.6). */
    void printRecovery(Instant resumedAt);
}
