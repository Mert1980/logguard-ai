package be.vdab.logguard.infrastructure.opensearch;

import be.vdab.logguard.infrastructure.config.LogguardProperties;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Builds the singleton {@link OpenSearchClient} from {@code LogguardProperties.opensearch.baseUrl}.
 * Local OpenSearch runs with security disabled (plain HTTP, no auth/TLS — Story 1.1), so no credentials.
 * The {@link JacksonJsonpMapper} is Jackson 2 (opensearch-java's own); it must not be wired to Boot's
 * Jackson 3 {@code ObjectMapper}.
 */
@Configuration
public class OpenSearchClientConfig {

    @Bean
    public OpenSearchClient openSearchClient(LogguardProperties properties) {
        URI uri = URI.create(properties.opensearch().baseUrl());
        HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());
        OpenSearchTransport transport = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper())
                .build();
        return new OpenSearchClient(transport);
    }
}
