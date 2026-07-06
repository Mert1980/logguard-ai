package be.vdab.logguard.infrastructure.persistence;

import be.vdab.logguard.domain.model.DeduplicationRecord;
import be.vdab.logguard.domain.model.ErrorFingerprint;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.port.out.DeduplicationRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the deduplication adapter against a real (in-memory) H2 with the V2 Flyway schema. The fact
 * that the context boots at all proves the entity mapping (incl. the {@code stored_analysis} JSON
 * converter, {@code @Lob} CLOBs, and {@code TIMESTAMP WITH TIME ZONE} columns) validates against the DDL
 * under {@code ddl-auto: validate}. {@code @Transactional} rolls back each test; {@code deleteAll()}
 * gives a clean slate.
 */
@SpringBootTest
@Transactional
class DeduplicationRecordRepositoryAdapterTest {

	@Autowired
	private DeduplicationRecordRepository repository;

	@Autowired
	private DeduplicationRecordJpaRepository jpaRepository;

	// Seed relative to the real clock: findActiveByFingerprint filters on Instant.now(), so a record must
	// have a genuinely-future expiresAt to count as active.
	private static final Duration WINDOW = Duration.ofHours(24);

	private static ErrorFingerprint fingerprint() {
		return new ErrorFingerprint("java.lang.NullPointerException",
				"Foo.bar:7", "be.vdab.app.Foo.bar");
	}

	@Test
	void savesThenFindsActiveRoundTripIncludingStoredAnalysis() {
		jpaRepository.deleteAll();
		ErrorFingerprint fp = fingerprint();
		LLMAnalysis analysis = LLMAnalysis.available("root cause", "Foo.bar:7", "do the fix");
		DeduplicationRecord record = DeduplicationRecord.createNew(fp, Instant.now(), WINDOW, false)
				.withStoredAnalysis(analysis);

		repository.save(record);

		Optional<DeduplicationRecord> loaded = repository.findActiveByFingerprint(fp);
		assertTrue(loaded.isPresent());
		DeduplicationRecord r = loaded.get();
		assertEquals(fp, r.fingerprint());
		assertEquals(fp.hash(), r.fingerprint().hash());
		assertEquals(1, r.occurrenceCount());
		assertFalse(r.wontFix());
		assertEquals(analysis, r.storedAnalysis(), "stored_analysis round-trips through the JSON converter");
	}

	@Test
	void expiredRecordIsNotReturnedAsActive() {
		jpaRepository.deleteAll();
		ErrorFingerprint fp = fingerprint();
		Instant past = Instant.now().minus(Duration.ofHours(1));
		repository.save(new DeduplicationRecord(fp, past.minus(WINDOW), past, 5, null, false, null));

		assertTrue(repository.findActiveByFingerprint(fp).isEmpty(), "expired ⇒ treated as absent (FR-9)");
	}

	@Test
	void saveUpsertsKeyedByFingerprintHash() {
		jpaRepository.deleteAll();
		ErrorFingerprint fp = fingerprint();
		Instant now = Instant.now();
		repository.save(DeduplicationRecord.createNew(fp, now, WINDOW, false));
		repository.save(DeduplicationRecord.createNew(fp, now, WINDOW, false).incrementOccurrence());

		assertEquals(1, jpaRepository.count(), "same fingerprint hash ⇒ one row");
		assertEquals(2, repository.findActiveByFingerprint(fp).orElseThrow().occurrenceCount());
	}

	@Test
	void deleteExpiredRemovesOnlyPastExpiryRows() {
		jpaRepository.deleteAll();
		ErrorFingerprint active = fingerprint();
		ErrorFingerprint expired = new ErrorFingerprint("java.lang.IllegalStateException",
				"Bar.baz:3", "be.vdab.app.Bar.baz");
		Instant nowReal = Instant.now();
		repository.save(new DeduplicationRecord(active, nowReal, nowReal.plus(WINDOW), 1, null, false, null));
		repository.save(new DeduplicationRecord(expired, nowReal.minus(WINDOW),
				nowReal.minus(Duration.ofHours(1)), 1, null, false, null));

		repository.deleteExpired(nowReal);

		assertEquals(1, jpaRepository.count());
		assertTrue(repository.findActiveByFingerprint(active).isPresent());
	}
}
