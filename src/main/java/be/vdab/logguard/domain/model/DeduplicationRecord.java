package be.vdab.logguard.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Deduplication state for one error fingerprint (FR-8/FR-9/FR-10/FR-25). Pure domain value object —
 * no Spring/JPA annotations (persistence lives in {@code infrastructure/persistence}). Identity is the
 * {@link ErrorFingerprint#hash()}; this record carries no persistence id.
 *
 * @param fingerprint           the structural identity of the error
 * @param firstSeenAt           when this fingerprint was first recorded in the current window
 * @param expiresAt             {@code firstSeenAt + deduplicationWindow}; once {@code expiresAt <= now} the
 *                              record is stale and the next encounter is treated as new
 * @param occurrenceCount       total hits in the window, including suppressed duplicates (FR-10)
 * @param lastNotifiedThreshold highest escalation threshold already fired; {@code null} until one fires (Story 4.5)
 * @param wontFix               true when the fingerprint hash is in the suppression file (Story 4.4)
 * @param storedAnalysis        cached LLM analysis, reused at escalation thresholds; {@code null} until set
 */
public record DeduplicationRecord(
        ErrorFingerprint fingerprint,
        Instant firstSeenAt,
        Instant expiresAt,
        int occurrenceCount,
        Integer lastNotifiedThreshold,
        boolean wontFix,
        LLMAnalysis storedAnalysis
) {

    /** A brand-new record: {@code occurrenceCount=1}, no threshold fired, no analysis yet. */
    public static DeduplicationRecord createNew(ErrorFingerprint fingerprint, Instant now,
                                                Duration window, boolean wontFix) {
        return new DeduplicationRecord(fingerprint, now, now.plus(window), 1, null, wontFix, null);
    }

    /** Same record with the occurrence count bumped by one (every other field unchanged). */
    public DeduplicationRecord incrementOccurrence() {
        return new DeduplicationRecord(fingerprint, firstSeenAt, expiresAt, occurrenceCount + 1,
                lastNotifiedThreshold, wontFix, storedAnalysis);
    }

    /** Same record with the cached analysis set (reused at escalation — no fresh LLM call, FR-25). */
    public DeduplicationRecord withStoredAnalysis(LLMAnalysis analysis) {
        return new DeduplicationRecord(fingerprint, firstSeenAt, expiresAt, occurrenceCount,
                lastNotifiedThreshold, wontFix, analysis);
    }
}
