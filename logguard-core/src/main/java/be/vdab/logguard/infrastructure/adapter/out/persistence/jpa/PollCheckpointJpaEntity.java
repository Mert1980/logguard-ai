package be.vdab.logguard.infrastructure.adapter.out.persistence.jpa;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "poll_checkpoint")
public class PollCheckpointJpaEntity {

    @Id
    private Long id;

    @Column(name = "last_successful_poll_at", nullable = false)
    private Instant lastSuccessfulPollAt;

    @Column(name = "consecutive_poll_failures", nullable = false)
    private int consecutivePollFailures;

    protected PollCheckpointJpaEntity() {}

    public PollCheckpointJpaEntity(Long id, Instant lastSuccessfulPollAt, int consecutivePollFailures) {
        this.id = id;
        this.lastSuccessfulPollAt = lastSuccessfulPollAt;
        this.consecutivePollFailures = consecutivePollFailures;
    }

    public Long getId() { return id; }
    public Instant getLastSuccessfulPollAt() { return lastSuccessfulPollAt; }
    public void setLastSuccessfulPollAt(Instant lastSuccessfulPollAt) { this.lastSuccessfulPollAt = lastSuccessfulPollAt; }
    public int getConsecutivePollFailures() { return consecutivePollFailures; }
    public void setConsecutivePollFailures(int consecutivePollFailures) { this.consecutivePollFailures = consecutivePollFailures; }
}
