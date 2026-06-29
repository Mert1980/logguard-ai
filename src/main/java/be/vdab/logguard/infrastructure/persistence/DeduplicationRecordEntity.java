package be.vdab.logguard.infrastructure.persistence;

import be.vdab.logguard.domain.model.LLMAnalysis;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA mapping for the {@code deduplication_record} table (one row per active fingerprint). Explicit
 * {@code @Table}/{@code @Column} names per AR-7. {@code Instant} fields are forced to
 * {@code TIMESTAMP WITH TIME ZONE} so Hibernate's {@code ddl-auto: validate} matches the Flyway DDL; the
 * fingerprint columns are denormalised copies and {@code fingerprint_hash} is the unique lookup key.
 */
@Entity
@Table(name = "deduplication_record")
public class DeduplicationRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "fingerprint_hash", nullable = false, unique = true, length = 64)
    private String fingerprintHash;

    @Column(name = "exception_type", length = 512)
    private String exceptionType;

    @Column(name = "throwing_method", length = 512)
    private String throwingMethod;

    @Lob
    @Column(name = "stack_trace_sequence")
    private String stackTraceSequence;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "last_notified_threshold")
    private Integer lastNotifiedThreshold;

    @Column(name = "wont_fix", nullable = false)
    private boolean wontFix;

    @Convert(converter = LlmAnalysisJsonConverter.class)
    @Lob
    @Column(name = "stored_analysis")
    private LLMAnalysis storedAnalysis;

    protected DeduplicationRecordEntity() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public String getFingerprintHash() {
        return fingerprintHash;
    }

    public void setFingerprintHash(String fingerprintHash) {
        this.fingerprintHash = fingerprintHash;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public void setExceptionType(String exceptionType) {
        this.exceptionType = exceptionType;
    }

    public String getThrowingMethod() {
        return throwingMethod;
    }

    public void setThrowingMethod(String throwingMethod) {
        this.throwingMethod = throwingMethod;
    }

    public String getStackTraceSequence() {
        return stackTraceSequence;
    }

    public void setStackTraceSequence(String stackTraceSequence) {
        this.stackTraceSequence = stackTraceSequence;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(int occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public Integer getLastNotifiedThreshold() {
        return lastNotifiedThreshold;
    }

    public void setLastNotifiedThreshold(Integer lastNotifiedThreshold) {
        this.lastNotifiedThreshold = lastNotifiedThreshold;
    }

    public boolean isWontFix() {
        return wontFix;
    }

    public void setWontFix(boolean wontFix) {
        this.wontFix = wontFix;
    }

    public LLMAnalysis getStoredAnalysis() {
        return storedAnalysis;
    }

    public void setStoredAnalysis(LLMAnalysis storedAnalysis) {
        this.storedAnalysis = storedAnalysis;
    }
}
