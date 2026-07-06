package be.vdab.logguard.domain.port.out;

import java.util.Set;

/**
 * Outbound port for the won't-fix suppression list (FR-14). Read-only by contract — there is no write
 * method on this interface under any circumstance (FR-16 / NFR-6). No Spring annotations.
 *
 * <p>{@code loadHashes()} is invoked at the START of every poll cycle, before any error is processed,
 * so a hot-edited file takes effect within one cycle (FR-14 ordering guarantee). The implementation
 * re-reads and parses the file each cycle (hot-reload), treats an absent file as "nothing suppressed"
 * (empty set), and on a read/parse failure keeps the last successfully loaded set ("unreadable → last
 * known state"). {@code loadHashes()} never throws, so this call site needs no guard.</p>
 */
public interface SuppressionFilePort {

    /** Hashes currently marked won't-fix. Empty set = nothing suppressed (also the absent-file case). */
    Set<String> loadHashes();
}
