package be.vdab.logguard.infrastructure.adapter.out.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PollCheckpointJpaRepository extends JpaRepository<PollCheckpointJpaEntity, Long> {}
