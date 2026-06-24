package be.vdab.logguard.domain.port.out;

import java.util.Set;

/**
 * Outbound port for the won't-fix suppression list (FR-14). Read-only by contract — there is no write
 * method on this interface under any circumstance (FR-16 / NFR-6). No Spring annotations.
 *
 * <p>{@code loadHashes()} is invoked at the START of every poll cycle, before any error is processed,
 * so a hot-edited file takes effect within one cycle (FR-14 ordering guarantee). The real parsing,
 * hot-reload, and "unreadable → last known state" behaviour is Story 4.3; until then a stub returns an
 * empty set (nothing suppressed).</p>
 */
public interface SuppressionFilePort {

    /** Hashes currently marked won't-fix. Empty set = nothing suppressed (also the absent-file case). */
    Set<String> loadHashes();
}
