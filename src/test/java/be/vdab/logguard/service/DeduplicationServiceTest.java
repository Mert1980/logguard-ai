package be.vdab.logguard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec: ProcessNewError rule
 *
 * When: ErrorDetected(error)
 * Requires: no unexpired DeduplicationRecord with matching fingerprint exists
 * Ensures:
 *   DeduplicationRecord.created(fingerprint, first_seen_at=now, expires_at=now+24h)
 *   ErrorReadyForAnalysis(error, fingerprint) emitted
 *
 * When requires fails (unexpired duplicate): silently discarded, nothing created, nothing emitted.
 *
 * Deduplication window: 24 hours (config.deduplication_window default)
 */
@DisplayName("DeduplicationService — ProcessNewError rule")
@ExtendWith(MockitoExtension.class)
class DeduplicationServiceTest {

    // TODO: inject once implemented
    // @InjectMocks DeduplicationService deduplicationService;
    // @Mock DeduplicationRecordRepository recordRepository;
    // @Mock ApplicationEventPublisher eventPublisher;

    @Nested
    @DisplayName("new error — no existing deduplication record")
    class NewError {

        @Test
        @DisplayName("creates a DeduplicationRecord")
        void newFingerprint_createsDedupRecord() {
            // TODO:
            // given: no DeduplicationRecord exists for the error's fingerprint
            // when:  deduplicationService.process(errorLog)
            // then:  recordRepository.save() is called with a new DeduplicationRecord
            //        whose fingerprint matches the error
        }

        @Test
        @DisplayName("DeduplicationRecord.expires_at is exactly first_seen_at plus 24 hours")
        void expiresAt_isFirstSeenAtPlusTwentyFourHours() {
            // TODO:
            // DeduplicationRecord saved = capturesSavedRecord();
            // assertThat(saved.getExpiresAt())
            //     .isEqualTo(saved.getFirstSeenAt().plus(24, ChronoUnit.HOURS));
        }

        @Test
        @DisplayName("emits ErrorReadyForAnalysis after recording the fingerprint")
        void newFingerprint_emitsReadyForAnalysis() {
            // TODO:
            // verify(eventPublisher).publishEvent(any(ErrorReadyForAnalysisEvent.class));
        }
    }

    @Nested
    @DisplayName("duplicate error — unexpired record exists")
    class DuplicateError {

        @Test
        @DisplayName("no new DeduplicationRecord is created")
        void duplicate_noDedupRecordCreated() {
            // TODO:
            // given: a DeduplicationRecord exists with expires_at = Instant.now().plus(1, HOURS)
            // when:  deduplicationService.process(errorLogWithSameFingerprint)
            // then:  recordRepository.save() is never called
        }

        @Test
        @DisplayName("no ErrorReadyForAnalysis is emitted")
        void duplicate_noAnalysisEmitted() {
            // TODO:
            // verify(eventPublisher, never()).publishEvent(any(ErrorReadyForAnalysisEvent.class));
        }

        @Test
        @DisplayName("same error for different KBO numbers is treated as a duplicate")
        void sameErrorDifferentKbo_treatedAsDuplicate() {
            // Spec: strip_tenant_data removes KBO numbers so errors for different tenants
            // with the same exception/method/flow share the same fingerprint
            // TODO:
            // given: DeduplicationRecord exists for fingerprint derived from KBO-001 error
            // when:  deduplicationService.process(identicalErrorForKbo002)
            // then:  treated as duplicate — no new record, no analysis emitted
        }

        @Test
        @DisplayName("same exception class in a different method is not a duplicate")
        void sameExceptionDifferentMethod_notDuplicate() {
            // Spec: "if the same exception class occurs in a different method it is not a duplicate"
            // TODO:
            // given: DeduplicationRecord exists for UserService.processRequest NullPointerException
            // when:  deduplicationService.process(nullPointerInUserService.validate)
            // then:  treated as new — new record created, analysis emitted
        }
    }

    @Nested
    @DisplayName("expired deduplication record")
    class ExpiredRecord {

        @Test
        @DisplayName("same error after the 24h window is treated as a new error")
        void sameErrorAfterWindow_treatedAsNew() {
            // TODO:
            // given: DeduplicationRecord exists with expires_at = Instant.now().minus(1, MINUTES)
            // when:  deduplicationService.process(sameErrorLog)
            // then:  a new DeduplicationRecord is created
            //        ErrorReadyForAnalysis is emitted
        }
    }
}
