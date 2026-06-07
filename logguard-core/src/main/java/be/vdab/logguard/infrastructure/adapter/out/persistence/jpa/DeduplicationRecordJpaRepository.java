package be.vdab.logguard.infrastructure.adapter.out.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface DeduplicationRecordJpaRepository extends JpaRepository<DeduplicationRecordJpaEntity, Long> {

    @Query("""
            SELECT COUNT(d) > 0 FROM DeduplicationRecordJpaEntity d
            WHERE d.exceptionType  = :exceptionType
              AND d.throwingMethod  = :throwingMethod
              AND d.expiresAt       > :now
            """)
    boolean existsActiveRecord(@Param("exceptionType") String exceptionType,
                               @Param("throwingMethod") String throwingMethod,
                               @Param("now") Instant now);
}
