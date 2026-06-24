package be.vdab.logguard.domain.model;

/**
 * Result of LLM root-cause analysis for one error (FR-23). Pure domain value object.
 *
 * <p>{@code llmAvailable=false} carries the {@code unavailabilityReason} instead of analysis fields
 * (FR-24) — an error is never silently dropped; it surfaces either with analysis or with the reason.</p>
 */
public record LLMAnalysis(
        boolean llmAvailable,
        String rootCause,
        String likelyLocation,
        String suggestedAction,
        String unavailabilityReason
) {

    public static LLMAnalysis available(String rootCause, String likelyLocation, String suggestedAction) {
        return new LLMAnalysis(true, rootCause, likelyLocation, suggestedAction, null);
    }

    public static LLMAnalysis unavailable(String reason) {
        return new LLMAnalysis(false, null, null, null, reason);
    }
}
