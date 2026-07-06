package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.DeduplicationRecord;
import be.vdab.logguard.domain.model.ErrorFingerprint;

import java.time.Instant;
import java.util.Optional;

/**
 * Outbound port for deduplication-record persistence. No Spring annotations.
 *
 * <p>{@link #findActiveByFingerprint} returns a record only while it is active (not past its
 * {@code expiresAt}); an expired row is invisible, so the caller treats it as a new error (FR-9).
 * {@link #save} is an upsert keyed by the fingerprint hash — one row per fingerprint.</p>
 */
public interface DeduplicationRecordRepository {

    /** The active (non-expired) record for this fingerprint, or empty if none/expired. */
    Optional<DeduplicationRecord> findActiveByFingerprint(ErrorFingerprint fingerprint);

    /** Insert or update (keyed by fingerprint hash) the record. */
    void save(DeduplicationRecord record);

    /** Remove records that expired before the given instant (housekeeping). */
    void deleteExpired(Instant before);
}
