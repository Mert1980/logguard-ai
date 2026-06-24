package be.vdab.logguard.infrastructure.llm;

/**
 * Structured-output target for the LLM's three-field response (FR-23). Spring AI's
 * {@code ChatClient.entity(...)} generates the JSON-schema instruction from this record and parses
 * the model's reply into it. Mapped into the domain {@code LLMAnalysis} by {@code LlmAdapter}.
 */
public record RootCauseAnalysis(
        String rootCause,
        String likelyLocation,
        String suggestedAction
) {
}
