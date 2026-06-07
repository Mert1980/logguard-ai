package be.vdab.logguard.infrastructure.adapter.out.persistence;

import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.out.CheckpointPersistencePort;
import be.vdab.logguard.infrastructure.adapter.out.persistence.jpa.PollCheckpointJpaEntity;
import be.vdab.logguard.infrastructure.adapter.out.persistence.jpa.PollCheckpointJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CheckpointAdapter implements CheckpointPersistencePort {

    private static final long SINGLETON_ID = 1L;

    private final PollCheckpointJpaRepository repository;

    public CheckpointAdapter(PollCheckpointJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public PollCheckpoint loadOrCreate() {
        return repository.findById(SINGLETON_ID)
                .map(e -> new PollCheckpoint(e.getId(), e.getLastSuccessfulPollAt(), e.getConsecutivePollFailures()))
                .orElseGet(() -> {
                    PollCheckpoint fresh = PollCheckpoint.createNew();
                    save(fresh);
                    return fresh;
                });
    }

    @Override
    @Transactional
    public void save(PollCheckpoint checkpoint) {
        PollCheckpointJpaEntity entity = repository.findById(SINGLETON_ID)
                .orElse(new PollCheckpointJpaEntity(SINGLETON_ID, checkpoint.getLastSuccessfulPollAt(), 0));
        entity.setLastSuccessfulPollAt(checkpoint.getLastSuccessfulPollAt());
        entity.setConsecutivePollFailures(checkpoint.getConsecutivePollFailures());
        repository.save(entity);
    }
}
