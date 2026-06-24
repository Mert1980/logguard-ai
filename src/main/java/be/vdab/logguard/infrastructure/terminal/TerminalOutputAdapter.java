package be.vdab.logguard.infrastructure.terminal;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.springframework.stereotype.Component;

/**
 * The only class permitted to write to {@code System.out} (AR-10). Prints the per-service error block
 * (FR-27/28/30) with the LLM analysis, or "analysis unavailable (reason)" when the LLM was unavailable (FR-24).
 */
@Component
public class TerminalOutputAdapter implements TerminalOutputPort {

    @Override
    public void printProgress(String serviceName, int count) {
        String plural = count == 1 ? "" : "s";
        System.out.println();
        System.out.println("Analyzing " + count + " new error" + plural + " in " + serviceName + "...");
        System.out.println("── " + serviceName + " (" + count + " error" + plural + ") ────────────────");
        System.out.flush();
    }

    @Override
    public void printAnalysis(int index, int total, ErrorLog error, LLMAnalysis analysis) {
        System.out.println("[" + index + "/" + total + "] " + error.exceptionType());
        if (analysis.llmAvailable()) {
            System.out.println("  Root cause:       " + dash(analysis.rootCause()));
            System.out.println("  Likely location:  " + dash(analysis.likelyLocation()));
            System.out.println("  Suggested action: " + dash(analysis.suggestedAction()));
        } else {
            System.out.println("  Root cause:       analysis unavailable (" + analysis.unavailabilityReason() + ")");
            System.out.println("  Likely location:  -");
            System.out.println("  Suggested action: -");
        }
        System.out.flush();
    }

    private static String dash(String value) {
        return (value == null || value.isBlank()) ? "-" : value.strip();
    }
}
