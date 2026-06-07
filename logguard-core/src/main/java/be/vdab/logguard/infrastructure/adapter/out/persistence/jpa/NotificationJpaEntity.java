package be.vdab.logguard.infrastructure.adapter.out.persistence.jpa;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification",
        indexes = @Index(name = "idx_notification_status_persisted",
                columnList = "status, persisted_at"))
public class NotificationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ErrorLog fields (embedded directly — no separate table needed)
    @Column(name = "error_exception_type", nullable = false, length = 500)
    private String errorExceptionType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_stack_trace", columnDefinition = "TEXT")
    private String errorStackTrace;

    @Column(name = "error_service_name", length = 255)
    private String errorServiceName;

    @Column(name = "error_app_name", length = 255)
    private String errorAppName;

    @Column(name = "error_team", length = 255)
    private String errorTeam;

    @Column(name = "error_environment", length = 255)
    private String errorEnvironment;

    @Column(name = "error_severity", length = 50)
    private String errorSeverity;

    @Column(name = "error_occurred_at")
    private Instant errorOccurredAt;

    // LLMAnalysis fields
    @Column(name = "llm_available", nullable = false)
    private boolean llmAvailable;

    @Column(name = "llm_summary", columnDefinition = "TEXT")
    private String llmSummary;

    @Column(name = "llm_root_cause", columnDefinition = "TEXT")
    private String llmRootCause;

    @Column(name = "llm_suggested_fix", columnDefinition = "TEXT")
    private String llmSuggestedFix;

    @Column(name = "llm_unavailability_reason", length = 1000)
    private String llmUnavailabilityReason;

    // GitLab links as JSON array
    @Column(name = "gitlab_links_json", columnDefinition = "TEXT")
    private String gitlabLinksJson;

    // Notification state
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "persisted_at")
    private Instant persistedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationJpaEntity() {}

    @PrePersist
    void onInsert() {
        this.createdAt = Instant.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getErrorExceptionType() { return errorExceptionType; }
    public void setErrorExceptionType(String v) { this.errorExceptionType = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public String getErrorStackTrace() { return errorStackTrace; }
    public void setErrorStackTrace(String v) { this.errorStackTrace = v; }
    public String getErrorServiceName() { return errorServiceName; }
    public void setErrorServiceName(String v) { this.errorServiceName = v; }
    public String getErrorAppName() { return errorAppName; }
    public void setErrorAppName(String v) { this.errorAppName = v; }
    public String getErrorTeam() { return errorTeam; }
    public void setErrorTeam(String v) { this.errorTeam = v; }
    public String getErrorEnvironment() { return errorEnvironment; }
    public void setErrorEnvironment(String v) { this.errorEnvironment = v; }
    public String getErrorSeverity() { return errorSeverity; }
    public void setErrorSeverity(String v) { this.errorSeverity = v; }
    public Instant getErrorOccurredAt() { return errorOccurredAt; }
    public void setErrorOccurredAt(Instant v) { this.errorOccurredAt = v; }
    public boolean isLlmAvailable() { return llmAvailable; }
    public void setLlmAvailable(boolean v) { this.llmAvailable = v; }
    public String getLlmSummary() { return llmSummary; }
    public void setLlmSummary(String v) { this.llmSummary = v; }
    public String getLlmRootCause() { return llmRootCause; }
    public void setLlmRootCause(String v) { this.llmRootCause = v; }
    public String getLlmSuggestedFix() { return llmSuggestedFix; }
    public void setLlmSuggestedFix(String v) { this.llmSuggestedFix = v; }
    public String getLlmUnavailabilityReason() { return llmUnavailabilityReason; }
    public void setLlmUnavailabilityReason(String v) { this.llmUnavailabilityReason = v; }
    public String getGitlabLinksJson() { return gitlabLinksJson; }
    public void setGitlabLinksJson(String v) { this.gitlabLinksJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int v) { this.attemptCount = v; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { this.failureReason = v; }
    public Instant getPersistedAt() { return persistedAt; }
    public void setPersistedAt(Instant v) { this.persistedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
