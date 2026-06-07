package be.vdab.logguard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec: AnalyseError rule
 *
 * When: ErrorReadyForAnalysis(error, fingerprint)
 * Ensures: Notification.created(
 *     error_log, analysis, gitlab_links, severity, status=pending, attempt_count=0)
 *
 * Analysis branch:
 *   - GeminiLLM.available = true  → full LLMAnalysis with summary/root_cause/suggested_fix
 *   - GeminiLLM.available = false → LLMAnalysis with llm_available=false, unavailability_reason set
 *
 * GitLab links: only frames matching own_code_package_prefix get links.
 * No mapping for app_name → empty links list.
 * severity: derived from ErrorLog.severity (the log.level field from OpenSearch).
 */
@DisplayName("ErrorAnalysisService — AnalyseError rule")
@ExtendWith(MockitoExtension.class)
class ErrorAnalysisServiceTest {

    // TODO: inject once implemented
    // @InjectMocks ErrorAnalysisService errorAnalysisService;
    // @Mock GeminiLLMClient geminiClient;
    // @Mock GitLabLinkResolver gitLabLinkResolver;
    // @Mock GitLabRepositoryRepository repositoryRepository;
    // @Mock NotificationRepository notificationRepository;

    @Nested
    @DisplayName("when LLM is available")
    class LlmAvailable {

        @Test
        @DisplayName("creates Notification with full LLM analysis")
        void llmAvailable_notificationWithFullAnalysis() {
            // TODO:
            // given: geminiClient.analyse(errorLog) returns an LLMAnalysis with
            //        summary="Summary", rootCause="Root cause", suggestedFix="Fix"
            // when:  errorAnalysisService.analyse(errorLog, fingerprint)
            // then:  a Notification is saved with:
            //          analysis.llm_available = true
            //          analysis.summary = "Summary"
            //          analysis.root_cause = "Root cause"
            //          analysis.suggested_fix = "Fix"
        }

        @Test
        @DisplayName("Notification is created with status=pending and attempt_count=0")
        void notificationCreated_pendingWithZeroAttempts() {
            // TODO:
            // assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
            // assertThat(notification.getAttemptCount()).isZero();
        }

        @Test
        @DisplayName("severity is derived from ErrorLog.severity (the log level field)")
        void severity_derivedFromErrorLogLevel() {
            // Spec: severity: String — derived from structured.log.level
            // For MVP, all processed logs have level=ERROR
            // TODO:
            // ErrorLog errorLog = buildErrorLog().withSeverity("ERROR");
            // assertThat(notification.getSeverity()).isEqualTo("ERROR");
        }
    }

    @Nested
    @DisplayName("when LLM is unavailable")
    class LlmUnavailable {

        @Test
        @DisplayName("creates Notification with llm_available=false and unavailability_reason")
        void llmUnavailable_notificationWithFallback() {
            // Spec: "send the notification anyway with just the raw error details and no AI analysis,
            //        but it should add info that says Gemini is unavailable and the reason"
            // TODO:
            // given: geminiClient is unavailable with reason "API quota exceeded"
            // then:  Notification saved with:
            //          analysis.llm_available = false
            //          analysis.unavailability_reason = "API quota exceeded"
        }

        @Test
        @DisplayName("Notification is still created and sent despite LLM failure")
        void llmUnavailable_notificationStillCreated() {
            // Spec: graceful degradation — notification is never dropped due to LLM failure
            // TODO:
            // assertThat(notification).isNotNull();
            // assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        }

        @Test
        @DisplayName("unavailability_reason defaults to 'unknown' when Gemini provides no reason")
        void llmUnavailable_noReason_defaultsToUnknown() {
            // Spec: GeminiLLM.unavailability_reason ?? "unknown"
            // TODO:
            // given: geminiClient is unavailable with no reason (null)
            // assertThat(notification.getAnalysis().getUnavailabilityReason()).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("GitLab link generation")
    class GitLabLinks {

        @Test
        @DisplayName("own-code frames produce GitLab links")
        void ownCodeFrames_haveLinks() {
            // TODO:
            // given: GitLabRepository mapping exists for app_name="my-service"
            //        stack trace contains be.vdab.app.service.UserService frame at line 42
            // then:  notification.gitlab_links contains one link with
            //          class_name="be.vdab.app.service.UserService"
            //          line_number=42
        }

        @Test
        @DisplayName("third-party frames do not produce GitLab links")
        void thirdPartyFrames_noLinks() {
            // TODO:
            // stack trace frames from org.springframework.*, java.*, javax.*, etc.
            // must not appear in notification.gitlab_links
        }

        @Test
        @DisplayName("class name is correctly converted to a source file path")
        void classToFilePath_correctConversion() {
            // Spec guidance: com.vdab.myapp.MyClass → src/main/java/com/vdab/myapp/MyClass.java
            // TODO:
            // assertThat(gitLabLinkResolver.classToPath("be.vdab.app.service.UserService"))
            //     .isEqualTo("src/main/java/be/vdab/app/service/UserService.java");
        }

        @Test
        @DisplayName("GitLab URL includes the line number as an anchor")
        void url_includesLineNumberAnchor() {
            // TODO:
            // assertThat(link.getUrl()).endsWith("#L42");
        }

        @Test
        @DisplayName("multiple own-code frames produce multiple links in stack trace order")
        void multipleOwnCodeFrames_multipleLinksInOrder() {
            // TODO:
            // given: stack trace has 2 own-code frames (UserService.java:42, UserController.java:18)
            // assertThat(notification.getGitlabLinks()).hasSize(2);
        }

        @Test
        @DisplayName("no GitLabRepository mapping for the app produces an empty links list")
        void noMapping_emptyLinks() {
            // Spec: "When no GitLabRepository mapping exists for the app, gitlab_links is empty"
            // TODO:
            // given: no GitLabRepository entry for app_name="unknown-service"
            // assertThat(notification.getGitlabLinks()).isEmpty();
        }
    }
}
