package be.vdab.logguard.infrastructure.persistence;

import be.vdab.logguard.domain.model.DeduplicationRecord;
import be.vdab.logguard.domain.model.ErrorFingerprint;
import be.vdab.logguard.domain.port.out.DeduplicationRecordRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence adapter implementing {@link DeduplicationRecordRepository}. {@link #save} upserts keyed by
 * the fingerprint hash (one row per fingerprint); {@link #findActiveByFingerprint} hides rows past their
 * {@code expiresAt} so the caller treats an expired fingerprint as new (FR-9).
 */
@Component
public class DeduplicationRecordRepositoryAdapter implements DeduplicationRecordRepository {

    private final DeduplicationRecordJpaRepository jpaRepository;

    public DeduplicationRecordRepositoryAdapter(DeduplicationRecordJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<DeduplicationRecord> findActiveByFingerprint(ErrorFingerprint fingerprint) {
        return jpaRepository.findByFingerprintHash(fingerprint.hash())
                .filter(entity -> entity.getExpiresAt().isAfter(Instant.now()))
                .map(this::toDomain);
    }

    @Override
    public void save(DeduplicationRecord record) {
        ErrorFingerprint fingerprint = record.fingerprint();
        DeduplicationRecordEntity entity = jpaRepository.findByFingerprintHash(fingerprint.hash())
                .orElseGet(DeduplicationRecordEntity::new);
        entity.setFingerprintHash(fingerprint.hash());
        entity.setExceptionType(fingerprint.exceptionType());
        entity.setThrowingMethod(fingerprint.throwingMethod());
        entity.setStackTraceSequence(fingerprint.stackTraceSequence());
        entity.setFirstSeenAt(record.firstSeenAt());
        entity.setExpiresAt(record.expiresAt());
        entity.setOccurrenceCount(record.occurrenceCount());
        entity.setLastNotifiedThreshold(record.lastNotifiedThreshold());
        entity.setWontFix(record.wontFix());
        entity.setStoredAnalysis(record.storedAnalysis());
        jpaRepository.save(entity);
    }

    @Override
    public void deleteExpired(Instant before) {
        jpaRepository.deleteByExpiresAtBefore(before);
    }

    private DeduplicationRecord toDomain(DeduplicationRecordEntity entity) {
        ErrorFingerprint fingerprint = new ErrorFingerprint(
                entity.getExceptionType(),
                entity.getThrowingMethod(),
                entity.getStackTraceSequence());
        return new DeduplicationRecord(
                fingerprint,
                entity.getFirstSeenAt(),
                entity.getExpiresAt(),
                entity.getOccurrenceCount(),
                entity.getLastNotifiedThreshold(),
                entity.isWontFix(),
                entity.getStoredAnalysis());
    }
}
