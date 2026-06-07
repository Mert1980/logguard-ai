package be.vdab.logguard.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class OpenSearchConfig {

    @Bean
    public OpenSearchClient openSearchClient(LogGuardProperties properties, ObjectMapper objectMapper) {
        URI uri = URI.create(properties.openSearch().uri());
        HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());

        RestClient restClient = RestClient.builder(host).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
        return new OpenSearchClient(transport);
    }
}
