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

    /**
     * FR-11/FR-12/FR-31: escalation re-notification when a known error's occurrence count crosses a
     * configured threshold. Reuses the cached {@code stored} analysis — never a fresh LLM call (FR-25).
     * Two shapes by {@code wontFix}: a cooling record prints the "Known error … Root cause:" block; a
     * won't-fix record prints the one-line "Won't-fix error … — volume is unusually high" volume override
     * (FR-12, fired only at the 1000× threshold; a read-only nudge that does not clear {@code wontFix}).
     *
     * @param humanLabel the computed fingerprint label ({@link be.vdab.logguard.domain.model.ErrorFingerprint#humanLabel()})
     * @param threshold  the threshold value just crossed (e.g. 10, 100, 1000)
     * @param firstSeen  when the fingerprint was first recorded in the current window
     * @param stored     the cached analysis to reuse; {@code null} → "no analysis on file" (cooling shape only)
     * @param wontFix    {@code true} selects the volume-override shape, {@code false} the cooling shape
     */
    void printEscalation(String humanLabel, int threshold, Instant firstSeen, LLMAnalysis stored, boolean wontFix);
}
