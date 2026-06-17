package be.vdab.logguard.infrastructure.opensearch;

import be.vdab.logguard.domain.model.ErrorLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Field-mapping unit test using a stubbed (canned) OpenSearch {@code _source} — the exact nested shape the
 * companion app writes (Story 1.2). Decodes with a Jackson 2 {@link ObjectMapper} (the same binding the
 * client's JacksonJsonpMapper uses) and asserts {@link OpenSearchAdapter#toErrorLog} maps every field.
 * No network / no Spring context — keeps {@code mvn test} hermetic.
 */
class OpenSearchAdapterTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String SOURCE = """
			{
			  "@timestamp": "2026-06-17T15:53:23.800Z",
			  "structured": {
			    "error": {
			      "type": "java.lang.NullPointerException",
			      "message": "Cannot invoke \\"String.toUpperCase()\\" because \\"resolved\\" is null",
			      "stack_trace": "java.lang.NullPointerException at be.vdab.logguard.producer.service.LabelService.forwardingSourceFor(LabelService.java:27)"
			    },
			    "service": { "name": "orgbeheer-service" },
			    "log": { "level": "ERROR" }
			  },
			  "kubernetes": {
			    "labels": { "appName": "logguard-error-producer" },
			    "namespace_labels": { "vdab_be_team": "backend-team", "vdab_be_environment": "local" }
			  },
			  "vdab": { "authorization": "cn=TESTUSER,ou=users,ou=intern,O=VDAB" }
			}
			""";

	@Test
	void mapsNestedSourceToErrorLog() throws Exception {
		OpenSearchErrorDocument doc = MAPPER.readValue(SOURCE, OpenSearchErrorDocument.class);

		ErrorLog log = OpenSearchAdapter.toErrorLog(doc);

		assertEquals("java.lang.NullPointerException", log.exceptionType());
		assertTrue(log.errorMessage().startsWith("Cannot invoke"));
		assertTrue(log.stackTrace().contains("be.vdab.logguard.producer.service.LabelService"));
		assertEquals("orgbeheer-service", log.serviceName());
		assertEquals("logguard-error-producer", log.appName());
		assertEquals("backend-team", log.team());
		assertEquals("local", log.environment());
		assertEquals(Instant.parse("2026-06-17T15:53:23.800Z"), log.occurredAt());
		assertEquals("cn=TESTUSER,ou=users,ou=intern,O=VDAB", log.vdabAuthorization());
	}

	@Test
	void toleratesUnknownAndMissingFields() throws Exception {
		OpenSearchErrorDocument doc = MAPPER.readValue(
				"{ \"@timestamp\": \"2026-06-17T10:00:00Z\", \"extra\": 42, \"structured\": { \"log\": { \"level\": \"ERROR\" } } }",
				OpenSearchErrorDocument.class);

		ErrorLog log = OpenSearchAdapter.toErrorLog(doc);

		assertEquals(Instant.parse("2026-06-17T10:00:00Z"), log.occurredAt());
		assertNull(log.exceptionType());
		assertNull(log.serviceName());
		assertNull(log.appName());
		assertNull(log.vdabAuthorization());
	}
}
