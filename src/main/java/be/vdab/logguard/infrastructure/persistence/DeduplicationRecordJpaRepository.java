package be.vdab.logguard.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DeduplicationRecordEntity}. One row per fingerprint hash.
 */
public interface DeduplicationRecordJpaRepository extends JpaRepository<DeduplicationRecordEntity, Long> {

    Optional<DeduplicationRecordEntity> findByFingerprintHash(String fingerprintHash);

    long deleteByExpiresAtBefore(Instant ts);
}
