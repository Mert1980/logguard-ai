package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;

/**
 * Outbound port for LLM root-cause analysis. No Spring annotations.
 *
 * <p>Contract: {@link #analyse} <b>never throws</b> — on any failure (LLM down, timeout, malformed
 * response) it returns {@code LLMAnalysis} with {@code llmAvailable=false} and a reason (FR-24).</p>
 */
public interface LlmPort {

    LLMAnalysis analyse(ErrorLog error);
}
