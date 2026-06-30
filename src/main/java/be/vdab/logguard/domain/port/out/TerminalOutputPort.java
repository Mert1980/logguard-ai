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

    /**
     * FR-30: one per-error block, showing the LLM analysis (or "analysis unavailable (reason)", FR-24),
     * ending with the {@code Fingerprint:} line (FR-18) so the {@code hash} can be copy-pasted into
     * {@code suppression.txt}.
     *
     * @param humanLabel the computed {@link be.vdab.logguard.domain.model.ErrorFingerprint#humanLabel()}
     * @param hash       the {@link be.vdab.logguard.domain.model.ErrorFingerprint#hash()} (the suppression key)
     */
    void printAnalysis(int index, int total, ErrorLog error, LLMAnalysis analysis, String humanLabel, String hash);

    /**
     * FR-17 / FR-32: the won't-fix acknowledgement label, printed once per new window when a suppressed
     * fingerprint is first encountered (won't-fix-from-birth). No analysis, no per-error block.
     *
     * @param humanLabel the computed fingerprint label
     * @param hash       the fingerprint hash (the suppression-file key)
     */
    void printWontFixLabel(String humanLabel, String hash);

    /** FR-34: sticky degradation banner, reprinted every cycle OpenSearch stays unreachable (Story 2.6). */
    void printDegraded(Instant degradationStartedAt, int consecutiveFailures);

    /** FR-35: one-time recovery line when OpenSearch becomes reachable again (Story 2.6). */
    void printRecovery(Instant resumedAt);

    /** FR-15: the suppression file existed but could not be read/parsed — keeping the last known state. */
    void printSuppressionUnreadable();
}
