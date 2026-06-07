package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.PollCheckpoint;

public interface CheckpointPersistencePort {
    PollCheckpoint loadOrCreate();
    void save(PollCheckpoint checkpoint);
}
