package be.vdab.logguard.infrastructure.opensearch;

import be.vdab.logguard.infrastructure.config.LogguardProperties;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the {@link OpenSearchClientConfig} bean factory builds a client across the local and secured
 * configurations without touching the network — the opensearch-java client connects lazily, so constructing
 * it exercises the auth/TLS wiring without a live cluster. Keeps {@code mvn test} hermetic.
 */
class OpenSearchClientConfigTest {

    private final OpenSearchClientConfig config = new OpenSearchClientConfig();

    @Test
    void buildsLocalPlainHttpClientWithoutAuthOrTls() {
        OpenSearchClient client = config.openSearchClient(propertiesWith(
                openSearch("http://localhost:9200", "", "", "")));

        assertNotNull(client);
    }

    @Test
    void buildsSecuredClientWithBasicAuthAndDefaultTrust() {
        // https + credentials, blank truststore → system-default trust; wiring must build without error.
        OpenSearchClient client = config.openSearchClient(propertiesWith(
                openSearch("https://opensearch.prod.example:9200", "svc-logguard", "s3cret", "")));

        assertNotNull(client);
    }

    @Test
    void buildsSecuredClientWithHttpsButNoCredentials() {
        // https without a username still wires TLS (system-default trust), no credentials provider.
        OpenSearchClient client = config.openSearchClient(propertiesWith(
                openSearch("https://opensearch.prod.example", "", "", "")));

        assertNotNull(client);
    }

    @Test
    void failsFastWhenTruststorePathIsUnreadable() {
        // A configured-but-missing truststore is a deployment error — surface it, don't silently ignore.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                config.openSearchClient(propertiesWith(openSearch(
                        "https://opensearch.prod.example:9200", "svc", "pw", "/no/such/truststore.p12"))));

        assertNotNull(ex.getCause());
    }

    private static LogguardProperties.OpenSearch openSearch(
            String baseUrl, String username, String password, String truststorePath) {
        return new LogguardProperties.OpenSearch(
                baseUrl,
                "logstash-app-openshift-application-springboot_error_*",
                Duration.ofSeconds(5),
                username,
                password,
                truststorePath,
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10));
    }

    private static LogguardProperties propertiesWith(LogguardProperties.OpenSearch opensearch) {
        return new LogguardProperties(
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                List.of(10, 100, 1000),
                3,
                List.of("be.vdab"),
                "./suppression.txt",
                new LogguardProperties.Ollama("http://localhost:11434", "gemma3:4b", Duration.ofSeconds(120)),
                opensearch,
                new LogguardProperties.H2("./data"),
                new LogguardProperties.GoogleChat(false, "", Duration.ofSeconds(10)));
    }
}
