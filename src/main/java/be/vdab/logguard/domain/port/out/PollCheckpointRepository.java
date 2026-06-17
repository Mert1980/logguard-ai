package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.PollCheckpoint;

import java.util.Optional;

/**
 * Outbound port for persisting the single {@link PollCheckpoint}. No Spring/JPA annotations.
 */
public interface PollCheckpointRepository {

    /** @return the stored checkpoint, or empty on first run (table empty). */
    Optional<PollCheckpoint> load();

    /** Persists the checkpoint, updating the single existing row if present. */
    void save(PollCheckpoint checkpoint);
}
