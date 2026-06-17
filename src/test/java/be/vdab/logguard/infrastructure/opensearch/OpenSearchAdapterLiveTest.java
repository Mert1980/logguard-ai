package be.vdab.logguard.infrastructure.opensearch;

import be.vdab.logguard.domain.model.ErrorLog;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration-gate check (AR-17): reads the companion app's documents from the running local
 * OpenSearch. SKIPPED unless {@code -Dopensearch.live=true} is passed, so {@code mvn test} stays hermetic
 * on machines without the Docker stack. Run with:
 * {@code mvn test -Dtest=OpenSearchAdapterLiveTest -Dopensearch.live=true} (stack up; a fresh error POSTed).
 */
class OpenSearchAdapterLiveTest {

	@Test
	void readsCompanionErrorsFromLocalOpenSearch() {
		assumeTrue(Boolean.getBoolean("opensearch.live"),
				"set -Dopensearch.live=true (and have docker compose up + a triggered error) to run");

		URI uri = URI.create("http://localhost:9200");
		HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());
		OpenSearchTransport transport = ApacheHttpClient5TransportBuilder.builder(host)
				.setMapper(new JacksonJsonpMapper())
				.build();
		OpenSearchClient client = new OpenSearchClient(transport);
		OpenSearchAdapter adapter =
				new OpenSearchAdapter(client, "logstash-app-openshift-application-springboot_error_*");

		List<ErrorLog> recent = adapter.findErrorsSince(Instant.now().minus(Duration.ofHours(1)));

		assertFalse(recent.isEmpty(), "expected companion errors within the last hour");
		ErrorLog first = recent.get(0);
		assertNotNull(first.exceptionType());
		assertNotNull(first.occurredAt());
		assertNotNull(first.serviceName());
		assertTrue(recent.stream()
						.anyMatch(e -> e.stackTrace() != null && e.stackTrace().contains("be.vdab")),
				"expected at least one stack trace with a be.vdab.* frame");

		// AC #3: a window with no documents returns empty without throwing.
		assertTrue(adapter.findErrorsSince(Instant.now().plus(Duration.ofDays(3650))).isEmpty());
	}
}
