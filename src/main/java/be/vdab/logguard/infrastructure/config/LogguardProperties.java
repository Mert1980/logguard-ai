package be.vdab.logguard.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Single binding point for all operational parameters (NFR-3, AR-11). All config lives under the
 * {@code logguard.*} prefix in {@code application.yml}; {@code @Value} is prohibited project-wide.
 *
 * <p>Every field carries a {@link DefaultValue} so the application is fully configured even with an
 * empty {@code logguard} block. Nested records ({@link Ollama}, {@link OpenSearch}, {@link H2}) are
 * themselves {@code @DefaultValue}-annotated so they materialise with their own defaults when absent.</p>
 */
@ConfigurationProperties(prefix = "logguard")
public record LogguardProperties(
        @DefaultValue("5m") Duration pollInterval,
        @DefaultValue("24h") Duration deduplicationWindow,
        @DefaultValue({"10", "100", "1000"}) List<Integer> escalationThresholds,
        @DefaultValue("3") int maxConsecutivePollFailures,
        @DefaultValue("be.vdab") List<String> ownCodePackagePrefixes,
        @DefaultValue("./suppression.txt") String suppressionFilePath,
        @DefaultValue Ollama ollama,
        @DefaultValue OpenSearch opensearch,
        @DefaultValue H2 h2
) {

    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("llama3") String model,
            @DefaultValue("30s") Duration timeout
    ) {
    }

    public record OpenSearch(
            @DefaultValue("http://localhost:9200") String baseUrl,
            @DefaultValue("logstash-app-openshift-application-springboot_error_*") String indexPattern
    ) {
    }

    public record H2(
            @DefaultValue("./data") String dataDir
    ) {
    }
}
