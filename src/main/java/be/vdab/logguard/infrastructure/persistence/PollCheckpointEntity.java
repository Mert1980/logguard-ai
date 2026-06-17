package be.vdab.logguard.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA mapping for the single-row {@code poll_checkpoint} table. Explicit {@code @Table}/{@code @Column}
 * names per AR-7 (no implicit Hibernate naming). The {@code Instant} fields are forced to
 * {@code TIMESTAMP WITH TIME ZONE} ({@link SqlTypes#TIMESTAMP_WITH_TIMEZONE}) so Hibernate's
 * {@code ddl-auto: validate} matches the Flyway-created column type.
 */
@Entity
@Table(name = "poll_checkpoint")
public class PollCheckpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "last_successful_poll_at", nullable = false)
    private Instant lastSuccessfulPollAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "degradation_started_at")
    private Instant degradationStartedAt;

    @Column(name = "consecutive_poll_failures", nullable = false)
    private int consecutivePollFailures;

    protected PollCheckpointEntity() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Instant getLastSuccessfulPollAt() {
        return lastSuccessfulPollAt;
    }

    public void setLastSuccessfulPollAt(Instant lastSuccessfulPollAt) {
        this.lastSuccessfulPollAt = lastSuccessfulPollAt;
    }

    public Instant getDegradationStartedAt() {
        return degradationStartedAt;
    }

    public void setDegradationStartedAt(Instant degradationStartedAt) {
        this.degradationStartedAt = degradationStartedAt;
    }

    public int getConsecutivePollFailures() {
        return consecutivePollFailures;
    }

    public void setConsecutivePollFailures(int consecutivePollFailures) {
        this.consecutivePollFailures = consecutivePollFailures;
    }
}
