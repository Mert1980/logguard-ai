package be.vdab.logguard.domain.model;

import java.time.Instant;

/**
 * Tracks the end of the last successful poll window.
 * The cursor never advances when OpenSearch is unreachable, enabling catch-up.
 */
public class PollCheckpoint {

    private final Long id;
    private Instant lastSuccessfulPollAt;
    private int consecutivePollFailures;

    public PollCheckpoint(Long id, Instant lastSuccessfulPollAt, int consecutivePollFailures) {
        this.id = id;
        this.lastSuccessfulPollAt = lastSuccessfulPollAt;
        this.consecutivePollFailures = consecutivePollFailures;
    }

    /** Creates a fresh checkpoint initialised to now (used on first run). */
    public static PollCheckpoint createNew() {
        return new PollCheckpoint(null, Instant.now(), 0);
    }

    public void recordSuccess() {
        this.lastSuccessfulPollAt = Instant.now();
        this.consecutivePollFailures = 0;
    }

    public void recordFailure() {
        this.consecutivePollFailures++;
    }

    /** Returns true exactly when the failure count first reaches the threshold — fires the alert once. */
    public boolean hasReachedDegradationThreshold(int threshold) {
        return consecutivePollFailures == threshold;
    }

    public Long getId() { return id; }
    public Instant getLastSuccessfulPollAt() { return lastSuccessfulPollAt; }
    public int getConsecutivePollFailures() { return consecutivePollFailures; }
}
