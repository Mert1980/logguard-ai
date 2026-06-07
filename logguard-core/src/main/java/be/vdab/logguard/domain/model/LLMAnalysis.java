package be.vdab.logguard.domain.model;

/**
 * Sealed interface matching the spec's LLMAnalysis value type.
 * The compiler enforces exhaustive switch — no null checks needed.
 */
public sealed interface LLMAnalysis {

    record Available(
            String summary,
            String rootCause,
            String suggestedFix
    ) implements LLMAnalysis {}

    record Unavailable(String reason) implements LLMAnalysis {}
}
