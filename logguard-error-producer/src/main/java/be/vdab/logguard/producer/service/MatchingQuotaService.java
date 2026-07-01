package be.vdab.logguard.producer.service;

import org.springframework.stereotype.Service;

/**
 * Stand-in VDAB own-code service whose failure path throws an {@link ArithmeticException}
 * from a {@code be.vdab.*} frame — a fourth distinct fingerprint for the demo. The exception
 * is raised by the JDK (integer divide-by-zero) but the topmost own-code frame is this method.
 */
@Service
public class MatchingQuotaService {

    /**
     * Simulates dividing a day's matches across the configured recruiters; when no recruiters
     * are configured this divides by zero and throws {@link ArithmeticException} ("/ by zero").
     */
    public int remainingQuota(int totalMatches, int recruiters) {
        return totalMatches / recruiters;
    }
}
