package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.ErrorLog;

import java.time.Instant;
import java.util.List;

/**
 * Outbound port for reading errors from OpenSearch. No Spring/JPA annotations.
 */
public interface OpenSearchPort {

    /**
     * Returns all errors with {@code occurred_at} strictly after {@code from}, oldest-or-newest order
     * unspecified. Returns an empty list when there are no matching documents. Transport/query failures
     * propagate (the poll loop's degradation logic handles outages — Story 2.6); they are NOT swallowed
     * into an empty list, which would hide an OpenSearch outage (NFR-2).
     */
    List<ErrorLog> findErrorsSince(Instant from);
}
