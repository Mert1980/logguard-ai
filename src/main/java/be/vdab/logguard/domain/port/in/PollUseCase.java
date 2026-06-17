package be.vdab.logguard.domain.port.in;

/**
 * Inbound (driving) port: run one poll cycle. No Spring annotations.
 */
public interface PollUseCase {

    /** Fetch errors since the checkpoint, deliver them to output, then advance the checkpoint. */
    void poll();
}
