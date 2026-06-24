package be.vdab.logguard;

import be.vdab.logguard.infrastructure.config.LogguardProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

@SpringBootTest
class LogguardAiApplicationTests {

	@Autowired
	private LogguardProperties properties;

	@Test
	void contextLoads() {
	}

	@Test
	void logguardPropertiesBindWithDefaults() {
		assertEquals(Duration.ofMinutes(5), properties.pollInterval());
		assertEquals(Duration.ofHours(24), properties.deduplicationWindow());
		assertIterableEquals(List.of(10, 100, 1000), properties.escalationThresholds());
		assertEquals(3, properties.maxConsecutivePollFailures());
		assertIterableEquals(List.of("be.vdab"), properties.ownCodePackagePrefixes());
		assertEquals("./suppression.txt", properties.suppressionFilePath());
		assertEquals("http://localhost:11434", properties.ollama().baseUrl());
		assertEquals("gemma3:4b", properties.ollama().model());
		assertEquals(Duration.ofSeconds(120), properties.ollama().timeout());
		assertEquals("http://localhost:9200", properties.opensearch().baseUrl());
		assertEquals("logstash-app-openshift-application-springboot_error_*",
				properties.opensearch().indexPattern());
		assertEquals("./data", properties.h2().dataDir());
	}

}
