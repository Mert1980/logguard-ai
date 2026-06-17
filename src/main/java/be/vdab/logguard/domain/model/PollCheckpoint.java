package be.vdab.logguard.domain.model;

import java.time.Instant;

/**
 * Poll cursor plus degradation state — the single point of truth for "where LogGuard left off".
 * Pure domain value object: no Spring or JPA annotations (persistence lives in
 * {@code infrastructure/persistence}).
 *
 * @param lastSuccessfulPollAt   errors with {@code occurred_at} after this instant are unprocessed
 * @param degradationStartedAt   when OpenSearch became unreachable; {@code null} when healthy (Story 2.6)
 * @param consecutivePollFailures consecutive failed poll cycles; resets to 0 on success (Story 2.6)
 */
public record PollCheckpoint(
        Instant lastSuccessfulPollAt,
        Instant degradationStartedAt,
        int consecutivePollFailures
) {
}
