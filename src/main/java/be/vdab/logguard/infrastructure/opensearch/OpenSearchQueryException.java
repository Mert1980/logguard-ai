package be.vdab.logguard.infrastructure.opensearch;

/**
 * Wraps a failed OpenSearch query/transport call. Propagated (not swallowed) so the poll loop's
 * degradation detection (Story 2.6) can count consecutive failures — a hidden outage would violate NFR-2.
 */
public class OpenSearchQueryException extends RuntimeException {

    public OpenSearchQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
