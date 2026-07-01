package be.vdab.logguard.producer.service;

import org.springframework.stereotype.Service;

/**
 * Stand-in VDAB own-code service whose failure path throws an {@link IllegalStateException}
 * from a {@code be.vdab.*} frame — a distinct exception type AND a distinct throwing method
 * from {@link LabelService}, so LogGuard computes a different fingerprint for it.
 */
@Service
public class VacatureService {

    /**
     * Simulates publishing a vacancy that is already closed — an invalid state transition,
     * one of the most common "should never happen" production errors.
     */
    public void publish(String vacatureId) {
        throw new IllegalStateException(
                "Vacature " + vacatureId + " cannot be published: current state is CLOSED");
    }
}
