package be.vdab.logguard.infrastructure.opensearch;

import be.vdab.logguard.infrastructure.config.LogguardProperties;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.nio.file.Path;

/**
 * Builds the singleton {@link OpenSearchClient} from {@code LogguardProperties.opensearch}.
 *
 * <p>The default target is the local Docker instance (plain HTTP, no auth/TLS — Story 1.1), for which the
 * client is built with only the mapper set — no credentials or transport customisation. When the configured
 * {@code base-url} is {@code https://} and/or a {@code username} is supplied (a secured production cluster,
 * e.g. OpenShift), this wires:</p>
 * <ul>
 *   <li><b>Basic auth</b> — a {@link BasicCredentialsProvider} scoped to the host, when a username is set;</li>
 *   <li><b>TLS</b> — for https, a connection manager whose {@link SSLContext} trusts either a private-CA
 *       truststore ({@code truststore-path}, typical for OpenShift) or, when blank, the JDK/system default
 *       (public-CA https);</li>
 *   <li><b>Timeouts</b> — connect/socket timeouts on the https connection manager so a hung prod endpoint
 *       cannot stall a poll cycle indefinitely.</li>
 * </ul>
 *
 * <p>The {@link JacksonJsonpMapper} is Jackson 2 (opensearch-java's own); it must not be wired to Boot's
 * Jackson 3 {@code ObjectMapper}.</p>
 */
@Configuration
public class OpenSearchClientConfig {

    @Bean
    public OpenSearchClient openSearchClient(LogguardProperties properties) {
        LogguardProperties.OpenSearch cfg = properties.opensearch();
        URI uri = URI.create(cfg.baseUrl());
        HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());

        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper());

        boolean secured = "https".equalsIgnoreCase(uri.getScheme());
        boolean authenticated = !cfg.username().isBlank();

        // Local plain-HTTP/no-auth path stays byte-for-byte as before: no callback, no customisation.
        if (secured || authenticated) {
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                if (authenticated) {
                    BasicCredentialsProvider credentials = new BasicCredentialsProvider();
                    credentials.setCredentials(new AuthScope(host),
                            new UsernamePasswordCredentials(cfg.username(), cfg.password().toCharArray()));
                    httpClientBuilder.setDefaultCredentialsProvider(credentials);
                }
                if (secured) {
                    httpClientBuilder.setConnectionManager(tlsConnectionManager(cfg));
                }
                return httpClientBuilder;
            });
        }

        return new OpenSearchClient(builder.build());
    }

    private PoolingAsyncClientConnectionManager tlsConnectionManager(LogguardProperties.OpenSearch cfg) {
        TlsStrategy tlsStrategy = ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext(cfg))
                .build();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(cfg.connectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(cfg.socketTimeout().toMillis()))
                .build();
        return PoolingAsyncClientConnectionManagerBuilder.create()
                .setTlsStrategy(tlsStrategy)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
    }

    private SSLContext sslContext(LogguardProperties.OpenSearch cfg) {
        if (cfg.truststorePath().isBlank()) {
            return SSLContexts.createSystemDefault();
        }
        char[] password = cfg.truststorePassword().isBlank() ? null : cfg.truststorePassword().toCharArray();
        try {
            return SSLContextBuilder.create()
                    .loadTrustMaterial(Path.of(cfg.truststorePath()), password)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load OpenSearch truststore from '" + cfg.truststorePath() + "'", e);
        }
    }
}
