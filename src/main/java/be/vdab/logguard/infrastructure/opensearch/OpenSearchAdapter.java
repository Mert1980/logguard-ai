package be.vdab.logguard.infrastructure.opensearch;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.port.out.OpenSearchPort;
import be.vdab.logguard.infrastructure.config.LogguardProperties;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Reads ERROR documents from OpenSearch via the official {@code opensearch-java} client (not Spring Data
 * OpenSearch). Queries the configured index pattern with a {@code @timestamp > from} range query (FR-4)
 * and maps each nested {@code _source} to an {@link ErrorLog}.
 */
@Component
public class OpenSearchAdapter implements OpenSearchPort {

    /** MVP cap — companion fixture volumes are tiny; pagination is out of scope (documented). */
    static final int MAX_RESULTS = 1000;
    private static final String TIMESTAMP_FIELD = "@timestamp";

    private final OpenSearchClient client;
    private final String indexPattern;

    @Autowired
    public OpenSearchAdapter(OpenSearchClient client, LogguardProperties properties) {
        this(client, properties.opensearch().indexPattern());
    }

    /** Package-private — lets tests/harnesses pass the index pattern without a full LogguardProperties. */
    OpenSearchAdapter(OpenSearchClient client, String indexPattern) {
        this.client = client;
        this.indexPattern = indexPattern;
    }

    @Override
    public List<ErrorLog> findErrorsSince(Instant from) {
        try {
            SearchResponse<OpenSearchErrorDocument> response = client.search(search -> search
                    .index(indexPattern)
                    .size(MAX_RESULTS)
                    .allowNoIndices(true)
                    .query(query -> query
                            .range(range -> range
                                    .field(TIMESTAMP_FIELD)
                                    .gt(JsonData.of(from.toString())))),
                    OpenSearchErrorDocument.class);
            return response.hits().hits().stream()
                    .map(hit -> hit.source())
                    .filter(Objects::nonNull)
                    .map(OpenSearchAdapter::toErrorLog)
                    .toList();
        } catch (IOException e) {
            // Propagate — degradation detection (Story 2.6) counts failures; swallowing would hide outages.
            throw new OpenSearchQueryException(
                    "Failed to query OpenSearch index pattern '" + indexPattern + "'", e);
        }
    }

    /** Maps the nested companion {@code _source} to the domain {@link ErrorLog}. Null-safe at each level. */
    static ErrorLog toErrorLog(OpenSearchErrorDocument doc) {
        OpenSearchErrorDocument.Structured structured = doc.structured;
        OpenSearchErrorDocument.Error error = structured != null ? structured.error : null;
        OpenSearchErrorDocument.Service service = structured != null ? structured.service : null;
        OpenSearchErrorDocument.Kubernetes kubernetes = doc.kubernetes;
        OpenSearchErrorDocument.Labels labels = kubernetes != null ? kubernetes.labels : null;
        OpenSearchErrorDocument.NamespaceLabels namespaceLabels =
                kubernetes != null ? kubernetes.namespaceLabels : null;

        return new ErrorLog(
                error != null ? error.type : null,
                error != null ? error.message : null,
                error != null ? error.stackTrace : null,
                service != null ? service.name : null,
                labels != null ? labels.appName : null,
                namespaceLabels != null ? namespaceLabels.team : null,
                namespaceLabels != null ? namespaceLabels.environment : null,
                doc.timestamp != null ? Instant.parse(doc.timestamp) : null,
                doc.vdab != null ? doc.vdab.authorization : null
        );
    }
}
