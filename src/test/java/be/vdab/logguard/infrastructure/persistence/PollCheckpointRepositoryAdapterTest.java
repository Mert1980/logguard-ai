package be.vdab.logguard.infrastructure.persistence;

import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the persistence adapter against a real (in-memory) H2 with Flyway-applied schema.
 * {@code @Transactional} rolls back each test; {@code deleteAll()} gives a clean slate regardless of
 * any checkpoint seeded by {@code FirstRunInitializer} at context startup.
 */
@SpringBootTest
@Transactional
class PollCheckpointRepositoryAdapterTest {

	@Autowired
	private PollCheckpointRepository repository;

	@Autowired
	private PollCheckpointJpaRepository jpaRepository;

	@Test
	void savesThenLoadsRoundTrip() {
		jpaRepository.deleteAll();
		Instant pollAt = Instant.parse("2026-06-01T10:00:00Z");

		repository.save(new PollCheckpoint(pollAt, null, 0));

		Optional<PollCheckpoint> loaded = repository.load();
		assertTrue(loaded.isPresent());
		assertEquals(pollAt, loaded.get().lastSuccessfulPollAt());
		assertNull(loaded.get().degradationStartedAt());
		assertEquals(0, loaded.get().consecutivePollFailures());
	}

	@Test
	void saveUpdatesTheSingleRowInsteadOfInsertingASecond() {
		jpaRepository.deleteAll();
		repository.save(new PollCheckpoint(Instant.parse("2026-06-01T10:00:00Z"), null, 0));
		repository.save(new PollCheckpoint(Instant.parse("2026-06-02T10:00:00Z"), null, 5));

		assertEquals(1, jpaRepository.count());
		PollCheckpoint loaded = repository.load().orElseThrow();
		assertEquals(Instant.parse("2026-06-02T10:00:00Z"), loaded.lastSuccessfulPollAt());
		assertEquals(5, loaded.consecutivePollFailures());
	}

	@Test
	void loadReturnsEmptyWhenNoCheckpointExists() {
		jpaRepository.deleteAll();
		assertTrue(repository.load().isEmpty());
	}
}
