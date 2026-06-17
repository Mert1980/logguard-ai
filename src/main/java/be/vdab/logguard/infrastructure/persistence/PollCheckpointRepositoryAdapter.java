package be.vdab.logguard.infrastructure.persistence;

import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Persistence adapter implementing {@link PollCheckpointRepository}. Keeps the {@code poll_checkpoint}
 * table at exactly one row: {@link #save} reuses the existing row's id so it updates rather than
 * inserting a second checkpoint.
 */
@Component
public class PollCheckpointRepositoryAdapter implements PollCheckpointRepository {

    private final PollCheckpointJpaRepository jpaRepository;

    public PollCheckpointRepositoryAdapter(PollCheckpointJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<PollCheckpoint> load() {
        return jpaRepository.findAll().stream().findFirst().map(this::toDomain);
    }

    @Override
    public void save(PollCheckpoint checkpoint) {
        PollCheckpointEntity entity = jpaRepository.findAll().stream()
                .findFirst()
                .orElseGet(PollCheckpointEntity::new);
        entity.setLastSuccessfulPollAt(checkpoint.lastSuccessfulPollAt());
        entity.setDegradationStartedAt(checkpoint.degradationStartedAt());
        entity.setConsecutivePollFailures(checkpoint.consecutivePollFailures());
        jpaRepository.save(entity);
    }

    private PollCheckpoint toDomain(PollCheckpointEntity entity) {
        return new PollCheckpoint(
                entity.getLastSuccessfulPollAt(),
                entity.getDegradationStartedAt(),
                entity.getConsecutivePollFailures()
        );
    }
}
