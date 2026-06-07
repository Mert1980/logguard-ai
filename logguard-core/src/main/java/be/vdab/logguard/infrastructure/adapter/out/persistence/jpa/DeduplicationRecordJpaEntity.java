package be.vdab.logguard.infrastructure.adapter.out.persistence.jpa;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "deduplication_record",
        indexes = @Index(name = "idx_dedup_exception_method_expires",
                columnList = "exception_type, throwing_method, expires_at"))
public class DeduplicationRecordJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exception_type", nullable = false, length = 500)
    private String exceptionType;

    @Column(name = "throwing_method", nullable = false, length = 500)
    private String throwingMethod;

    @Column(name = "stack_trace_sequence", nullable = false, columnDefinition = "TEXT")
    private String stackTraceSequence;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DeduplicationRecordJpaEntity() {}

    public DeduplicationRecordJpaEntity(String exceptionType, String throwingMethod,
                                        String stackTraceSequence, Instant firstSeenAt, Instant expiresAt) {
        this.exceptionType      = exceptionType;
        this.throwingMethod     = throwingMethod;
        this.stackTraceSequence = stackTraceSequence;
        this.firstSeenAt        = firstSeenAt;
        this.expiresAt          = expiresAt;
    }

    public Long getId() { return id; }
    public String getExceptionType() { return exceptionType; }
    public String getThrowingMethod() { return throwingMethod; }
    public String getStackTraceSequence() { return stackTraceSequence; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
