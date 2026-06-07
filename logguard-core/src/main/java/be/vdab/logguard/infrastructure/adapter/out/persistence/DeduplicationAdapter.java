package be.vdab.logguard.infrastructure.adapter.out.persistence;

import be.vdab.logguard.domain.model.ErrorFingerprint;
import be.vdab.logguard.domain.port.out.DeduplicationPort;
import be.vdab.logguard.infrastructure.adapter.out.persistence.jpa.DeduplicationRecordJpaEntity;
import be.vdab.logguard.infrastructure.adapter.out.persistence.jpa.DeduplicationRecordJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Component
public class DeduplicationAdapter implements DeduplicationPort {

    private final DeduplicationRecordJpaRepository repository;

    public DeduplicationAdapter(DeduplicationRecordJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isDuplicate(ErrorFingerprint fingerprint) {
        return repository.existsActiveRecord(
                fingerprint.exceptionType(),
                fingerprint.throwingMethod(),
                Instant.now());
    }

    @Override
    @Transactional
    public void record(ErrorFingerprint fingerprint, Duration window) {
        Instant now = Instant.now();
        repository.save(new DeduplicationRecordJpaEntity(
                fingerprint.exceptionType(),
                fingerprint.throwingMethod(),
                fingerprint.stackTraceSequence(),
                now,
                now.plus(window)));
    }
}
