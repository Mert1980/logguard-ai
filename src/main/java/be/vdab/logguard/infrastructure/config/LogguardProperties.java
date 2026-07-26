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
        @DefaultValue H2 h2,
        @DefaultValue GoogleChat googleChat
) {

    /**
     * Google Chat delivery: when {@code enabled}, the {@code GoogleChatOutputAdapter} becomes the primary
     * {@link be.vdab.logguard.domain.port.out.TerminalOutputPort} and every notification is POSTed to the
     * incoming-webhook {@code webhookUrl} instead of {@code System.out}. The URL carries a space key/token
     * secret, so it lives in {@code application.yml} (overridable via {@code LOGGUARD_GOOGLE_CHAT_WEBHOOK_URL})
     * — never a hard-coded default.
     */
    public record GoogleChat(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String webhookUrl,
            @DefaultValue("10s") Duration timeout
    ) {
    }

    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("gemma3:4b") String model,
            @DefaultValue("120s") Duration timeout
    ) {
    }

    /**
     * OpenSearch connection. Defaults target the local Docker instance (plain HTTP, no auth/TLS — Story 1.1).
     * A secured production cluster is reached by overriding {@code baseUrl} with an {@code https://} URL and
     * supplying {@code username}/{@code password}; the client then wires basic-auth and TLS. Credentials carry
     * secrets, so — like {@link GoogleChat#webhookUrl} — they have blank defaults and come from the environment
     * ({@code LOGGUARD_OPENSEARCH_USERNAME} / {@code LOGGUARD_OPENSEARCH_PASSWORD}), never a hard-coded value.
     * {@code truststorePath} is optional and only needed for a private/self-signed CA; blank uses JDK default
     * trust. {@code connectTimeout}/{@code socketTimeout} apply to the transport when connecting over https.
     */
    public record OpenSearch(
            @DefaultValue("http://localhost:9200") String baseUrl,
            @DefaultValue("logstash-app-openshift-application-springboot_error_*") String indexPattern,
            @DefaultValue("5s") Duration refreshWindow,
            @DefaultValue("") String username,
            @DefaultValue("") String password,
            @DefaultValue("") String truststorePath,
            @DefaultValue("") String truststorePassword,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration socketTimeout
    ) {
    }

    public record H2(
            @DefaultValue("./data") String dataDir
    ) {
    }
}
