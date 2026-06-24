package be.vdab.logguard.infrastructure.llm;

import be.vdab.logguard.domain.model.LLMAnalysis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-24: the LLM result mapping. A valid three-field reply becomes an available analysis; a null/blank
 * reply becomes "malformed response" (never a thrown exception / dropped error). The transport-failure
 * path is a plain try/catch in {@code analyse} and is exercised live (LLM down ⇒ "analysis unavailable").
 */
class LlmAdapterTest {

	@Test
	void validReplyBecomesAvailableAnalysis() {
		LLMAnalysis result = LlmAdapter.toAnalysis(
				new RootCauseAnalysis("NPE on null forwarding source", "LabelService.forwardingSourceFor", "null-check the lookup"));
		assertTrue(result.llmAvailable());
		assertEquals("NPE on null forwarding source", result.rootCause());
		assertEquals("LabelService.forwardingSourceFor", result.likelyLocation());
		assertEquals("null-check the lookup", result.suggestedAction());
	}

	@Test
	void nullReplyIsMalformed() {
		LLMAnalysis result = LlmAdapter.toAnalysis(null);
		assertFalse(result.llmAvailable());
		assertEquals("malformed response", result.unavailabilityReason());
	}

	@Test
	void blankRootCauseIsMalformed() {
		LLMAnalysis result = LlmAdapter.toAnalysis(new RootCauseAnalysis("  ", "x", "y"));
		assertFalse(result.llmAvailable());
		assertEquals("malformed response", result.unavailabilityReason());
	}
}
