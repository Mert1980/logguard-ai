package be.vdab.logguard.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the full error detection and notification flow.
 *
 * These tests exercise the complete chain:
 *   OpenSearch → ErrorPollingService → DeduplicationService
 *     → ErrorAnalysisService → NotificationDeliveryService → Google Chat
 *
 * Wire all components together using test doubles for the three external systems:
 *   - OpenSearchClient (returns pre-configured error logs)
 *   - GeminiLLMClient  (returns controlled analysis or simulates unavailability)
 *   - GoogleChatWebhookClient (records sent payloads or simulates failures)
 *
 * TODO: add @SpringBootTest once the application context is bootstrapped,
 *       or wire components manually if a lightweight integration harness is preferred.
 */
@DisplayName("Error detection flow — end-to-end scenarios")
class ErrorDetectionFlowTest {

    @Test
    @DisplayName("happy path: new error is analysed and notification is delivered")
    void happyPath_newError_notificationDelivered() {
        // TODO:
        // Given: OpenSearch returns 1 new ERROR log for app "my-service"
        //        Gemini returns a successful analysis (summary, root_cause, suggested_fix)
        //        Google Chat webhook accepts the POST
        //        GitLabRepository mapping exists for "my-service"
        // When:  poll cycle fires
        // Then:  1 Notification is saved with status=DELIVERED
        //        notification.analysis.llm_available = true
        //        notification.gitlab_links contains links for own-code frames only
        //        notification.severity = "ERROR"
    }

    @Test
    @DisplayName("duplicate suppression: same error twice within 24h produces one notification")
    void duplicateSuppression_twoOccurrences_oneNotification() {
        // TODO:
        // Given: OpenSearch returns 2 ERROR logs with the same fingerprint
        //        (same exception_type, throwing_method, stack_trace — different KBO numbers)
        // When:  poll cycle fires
        // Then:  only 1 DeduplicationRecord is created
        //        only 1 Notification is created and delivered
        //        the second log is silently discarded
    }

    @Test
    @DisplayName("LLM fallback: Gemini unavailable — notification sent with raw error and unavailability note")
    void llmFallback_geminiUnavailable_rawNotificationDelivered() {
        // Spec: "send the notification anyway with just the raw error details and no AI analysis,
        //        but it should add info that says Gemini is unavailable and the reason"
        // TODO:
        // Given: Gemini is unavailable (throws / returns error with reason "quota exceeded")
        //        OpenSearch returns 1 new ERROR log
        // When:  poll cycle fires
        // Then:  1 Notification is created with:
        //          analysis.llm_available = false
        //          analysis.unavailability_reason = "quota exceeded"
        //        notification.status = DELIVERED  (webhook still succeeds)
    }

    @Test
    @DisplayName("webhook retry: 3 failures → persisted → next poll → delivered")
    void webhookRetry_persistedAfterMaxAttempts_deliveredOnNextPoll() {
        // TODO:
        // Given: webhook fails on every attempt (first poll cycle)
        // When:  3 delivery attempts are made (max_delivery_attempts=3)
        // Then:  notification.status = FAILED, notification.persisted_at is set
        // When:  next poll cycle fires (RetryPersistedNotifications resets to pending)
        //        webhook succeeds this time
        // Then:  notification.status = DELIVERED
    }

    @Test
    @DisplayName("OpenSearch catch-up: failed poll leaves cursor unchanged; next poll covers missed window")
    void openSearchCatchUp_failedPoll_nextPollCoversFullGap() {
        // Spec: cursor not advanced when OpenSearch is unreachable, enabling catch-up
        // TODO:
        // Given: checkpoint is at T0
        //        poll at T1 fails (OpenSearch unreachable) → cursor stays at T0
        //        an ERROR log occurs at T0+30min (during the outage window)
        // When:  poll at T2 succeeds (OpenSearch back online)
        //        query covers occurred_at > T0 (not T1)
        // Then:  the error from T0+30min is processed and a notification is delivered
    }

    @Test
    @DisplayName("same error after 24h window is treated as new and re-analysed")
    void deduplicationExpiry_sameErrorAfterWindow_reanalysed() {
        // TODO:
        // Given: error with fingerprint F is processed at T0 (DeduplicationRecord expires at T0+24h)
        // When:  same error occurs at T0+25h (after the window)
        // Then:  a new DeduplicationRecord is created
        //        a new Notification is created and delivered
    }
}
