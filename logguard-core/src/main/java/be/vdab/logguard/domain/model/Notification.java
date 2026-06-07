package be.vdab.logguard.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Domain entity for a notification pending delivery to Google Chat.
 * Status transitions mirror the spec's transition graph:
 *   pending → delivered (terminal)
 *   pending → failed
 *   failed  → pending  (retry)
 */
public class Notification {

    private Long id;
    private final ErrorLog errorLog;
    private final LLMAnalysis analysis;
    private final List<GitLabLink> gitLabLinks;
    private NotificationStatus status;
    private int attemptCount;
    private String failureReason;
    private Instant persistedAt;

    /** Used when creating a new notification from an error. */
    public Notification(ErrorLog errorLog, LLMAnalysis analysis, List<GitLabLink> gitLabLinks) {
        this.errorLog = Objects.requireNonNull(errorLog);
        this.analysis = Objects.requireNonNull(analysis);
        this.gitLabLinks = List.copyOf(Objects.requireNonNull(gitLabLinks));
        this.status = NotificationStatus.PENDING;
        this.attemptCount = 0;
    }

    /** Used by the persistence adapter to reconstitute a stored notification. */
    public Notification(Long id, ErrorLog errorLog, LLMAnalysis analysis, List<GitLabLink> gitLabLinks,
                        NotificationStatus status, int attemptCount, String failureReason, Instant persistedAt) {
        this.id = id;
        this.errorLog = Objects.requireNonNull(errorLog);
        this.analysis = Objects.requireNonNull(analysis);
        this.gitLabLinks = List.copyOf(Objects.requireNonNull(gitLabLinks));
        this.status = Objects.requireNonNull(status);
        this.attemptCount = attemptCount;
        this.failureReason = failureReason;
        this.persistedAt = persistedAt;
    }

    public void markDelivered() {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException("Only PENDING notifications can be marked delivered, current: " + status);
        }
        this.status = NotificationStatus.DELIVERED;
    }

    /**
     * Records a failed delivery attempt.
     * Increments the counter; transitions to FAILED and sets persistedAt when maxAttempts is reached.
     * Matches spec rule NotificationDeliveryFailed: ensures block reads the resulting count.
     */
    public void recordFailure(String reason, int maxAttempts) {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException("Only PENDING notifications can record failure, current: " + status);
        }
        this.failureReason = reason;
        this.attemptCount++;
        if (this.attemptCount >= maxAttempts) {
            this.status = NotificationStatus.FAILED;
            this.persistedAt = Instant.now();
        }
    }

    /** Resets a FAILED notification back to PENDING for retry. */
    public void resetForRetry() {
        if (status != NotificationStatus.FAILED) {
            throw new IllegalStateException("Only FAILED notifications can be retried, current: " + status);
        }
        this.status = NotificationStatus.PENDING;
        this.attemptCount = 0;
    }

    public boolean isPersisted() {
        return persistedAt != null;
    }

    // Getters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ErrorLog getErrorLog() { return errorLog; }
    public LLMAnalysis getAnalysis() { return analysis; }
    public List<GitLabLink> getGitLabLinks() { return gitLabLinks; }
    public NotificationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getFailureReason() { return failureReason; }
    public Instant getPersistedAt() { return persistedAt; }
}
