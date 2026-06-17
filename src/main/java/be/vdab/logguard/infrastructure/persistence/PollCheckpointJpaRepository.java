package be.vdab.logguard.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PollCheckpointEntity}. The table holds at most one row.
 */
public interface PollCheckpointJpaRepository extends JpaRepository<PollCheckpointEntity, Long> {
}
