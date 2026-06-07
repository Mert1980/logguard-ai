package be.vdab.logguard.infrastructure.adapter.out.opensearch;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.port.out.ErrorLogSearchPort;
import be.vdab.logguard.infrastructure.config.LogGuardProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class OpenSearchLogAdapter implements ErrorLogSearchPort {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchLogAdapter.class);

    private final OpenSearchClient client;
    private final LogGuardProperties properties;
    private final ObjectMapper objectMapper;

    public OpenSearchLogAdapter(OpenSearchClient client,
                                LogGuardProperties properties,
                                ObjectMapper objectMapper) {
        this.client       = client;
        this.properties   = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isReachable() {
        try {
            client.info();
            return true;
        } catch (Exception e) {
            log.warn("OpenSearch unreachable: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<ErrorLog> findSince(Instant since) {
        try {
            Query rangeQuery = Query.of(q -> q
                    .range(r -> r
                            .field("@timestamp")
                            .gt(co.elastic.clients.json.JsonData.of(since.toString()))
                    )
            );

            SearchResponse<JsonNode> response = client.search(
                    s -> s.index(properties.openSearch().indexName())
                          .query(rangeQuery)
                          .size(500),
                    JsonNode.class);

            return response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(source -> source != null)
                    .map(this::mapToErrorLog)
                    .filter(el -> el != null)
                    .toList();

        } catch (Exception e) {
            log.error("Failed to search OpenSearch: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private ErrorLog mapToErrorLog(JsonNode source) {
        try {
            return new ErrorLog(
                    textAt(source, "/structured/error/type"),
                    textAt(source, "/structured/error/message"),
                    textAt(source, "/structured/error/stack_trace"),
                    textAt(source, "/structured/service/name"),
                    textAt(source, "/kubernetes/labels/appName"),
                    textAt(source, "/kubernetes/namespace_labels/vdab_be_team"),
                    textAt(source, "/kubernetes/namespace_labels/vdab_be_environment"),
                    textAt(source, "/structured/log/level"),
                    Instant.parse(textAt(source, "/@timestamp"))
            );
        } catch (Exception e) {
            log.warn("Skipping malformed OpenSearch document: {}", e.getMessage());
            return null;
        }
    }

    private String textAt(JsonNode node, String pointer) {
        JsonNode found = node.at(pointer);
        return found.isMissingNode() ? "" : found.asText();
    }
}
