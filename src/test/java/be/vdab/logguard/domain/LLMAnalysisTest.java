package be.vdab.logguard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec: LLMAnalysis value type — state-dependent field presence
 *
 * When llm_available = true:
 *   summary, root_cause, suggested_fix are present
 *   unavailability_reason is absent
 *
 * When llm_available = false:
 *   unavailability_reason is present
 *   summary, root_cause, suggested_fix are absent
 *   unavailability_reason defaults to "unknown" when Gemini provides no reason
 */
@DisplayName("LLMAnalysis")
class LLMAnalysisTest {

    // TODO: replace with actual LLMAnalysis factory methods once implemented

    @Nested
    @DisplayName("when LLM is available")
    class WhenAvailable {

        @Test
        @DisplayName("summary, root_cause and suggested_fix are present")
        void analysisFields_present() {
            // TODO:
            // LLMAnalysis analysis = LLMAnalysis.available("A summary", "The root cause", "A fix suggestion");
            // assertThat(analysis.getSummary()).isEqualTo("A summary");
            // assertThat(analysis.getRootCause()).isEqualTo("The root cause");
            // assertThat(analysis.getSuggestedFix()).isEqualTo("A fix suggestion");
        }

        @Test
        @DisplayName("unavailability_reason is absent")
        void unavailabilityReason_absent() {
            // TODO:
            // LLMAnalysis analysis = LLMAnalysis.available("Summary", "Root cause", "Fix");
            // assertThat(analysis.getUnavailabilityReason()).isNull();
        }

        @Test
        @DisplayName("llm_available is true")
        void llmAvailable_isTrue() {
            // TODO:
            // LLMAnalysis analysis = LLMAnalysis.available("Summary", "Root cause", "Fix");
            // assertThat(analysis.isLlmAvailable()).isTrue();
        }
    }

    @Nested
    @DisplayName("when LLM is unavailable")
    class WhenUnavailable {

        @Test
        @DisplayName("unavailability_reason is present")
        void unavailabilityReason_present() {
            // TODO:
            // LLMAnalysis analysis = LLMAnalysis.unavailable("Service quota exceeded");
            // assertThat(analysis.getUnavailabilityReason()).isEqualTo("Service quota exceeded");
        }

        @Test
        @DisplayName("summary, root_cause and suggested_fix are absent")
        void analysisFields_absent() {
            // TODO:
            // LLMAnalysis analysis = LLMAnalysis.unavailable("Service quota exceeded");
            // assertThat(analysis.getSummary()).isNull();
            // assertThat(analysis.getRootCause()).isNull();
            // assertThat(analysis.getSuggestedFix()).isNull();
        }

        @Test
        @DisplayName("llm_available is false")
        void llmAvailable_isFalse() {
            // TODO:
            // LLMAnalysis analysis = LLMAnalysis.unavailable("Service quota exceeded");
            // assertThat(analysis.isLlmAvailable()).isFalse();
        }

        @Test
        @DisplayName("unavailability_reason defaults to 'unknown' when Gemini provides no reason")
        void unavailabilityReason_defaultsToUnknown() {
            // Spec: GeminiLLM.unavailability_reason ?? "unknown"
            // TODO:
            // LLMAnalysis analysis = LLMAnalysis.unavailable(null);
            // assertThat(analysis.getUnavailabilityReason()).isEqualTo("unknown");
        }
    }
}
