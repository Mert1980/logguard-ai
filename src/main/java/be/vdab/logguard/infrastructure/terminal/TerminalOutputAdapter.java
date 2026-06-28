package be.vdab.logguard.infrastructure.terminal;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only class permitted to write to {@code System.out} (AR-10). Prints the per-service error block
 * (FR-27/28/30) with the LLM analysis, or "analysis unavailable (reason)" when the LLM was unavailable (FR-24).
 */
@Component
public class TerminalOutputAdapter implements TerminalOutputPort {

    /** Matches the topmost stack frame: {@code at <fqcn>.<method>(...)} — group 1 is the FQCN. */
    private static final Pattern FIRST_FRAME = Pattern.compile("(?m)^\\s*at\\s+([\\w$.]+)\\.[\\w$<>]+\\(");

    /** Cap the unavailability reason so a long/multi-line message can't blow the aligned block. */
    private static final int MAX_REASON_LENGTH = 100;

    /**
     * FR-31/32/34/35 status indicators. Defined here (never inline literals) per Story 2.4 AC; the
     * emitters land in Story 2.6 (DEGRADED/RECOVERED) and Epic 4 (WONT_FIX/ESCALATION), so these are
     * intentionally unused until then.
     */
    static final String DEGRADED = "🔴";
    static final String RECOVERED = "🟢";
    static final String WONT_FIX = "⚑";
    static final String ESCALATION = "⚠️";

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
        System.out.println("[" + index + "/" + total + "] " + header(error));
        if (analysis.llmAvailable()) {
            System.out.println("  Root cause:       " + dash(analysis.rootCause()));
            System.out.println("  Likely location:  " + dash(analysis.likelyLocation()));
            System.out.println("  Suggested action: " + dash(analysis.suggestedAction()));
        } else {
            System.out.println("  Root cause:       analysis unavailable (" + reason(analysis.unavailabilityReason()) + ")");
            System.out.println("  Likely location:  -");
            System.out.println("  Suggested action: -");
        }
        System.out.flush();
    }

    private static String dash(String value) {
        return (value == null || value.isBlank()) ? "-" : value.strip();
    }

    /** One-line, length-capped unavailability reason; blank ⇒ "reason unknown" (never "()"). */
    private static String reason(String raw) {
        if (raw == null || raw.isBlank()) {
            return "reason unknown";
        }
        String oneLine = raw.strip().replaceAll("\\s+", " ");
        return oneLine.length() > MAX_REASON_LENGTH
                ? oneLine.substring(0, MAX_REASON_LENGTH - 1) + "…"
                : oneLine;
    }

    /** FR-30 header: {@code {ExceptionType}@{ClassName}}; drops the {@code @class} suffix if undetectable. */
    private static String header(ErrorLog error) {
        String exceptionType = dash(error.exceptionType());   // "-" instead of the literal "null"
        String throwingClass = throwingClass(error.stackTrace());
        return throwingClass == null ? exceptionType : exceptionType + "@" + throwingClass;
    }

    /**
     * Simple class name of the topmost stack frame (the throw site). Refining this to the first own-code
     * frame for the won't-fix HumanLabel is Epic 4 ({@code FingerprintService}); the block header here
     * just needs {@code @{ClassName}}.
     */
    private static String throwingClass(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return null;
        }
        Matcher matcher = FIRST_FRAME.matcher(stackTrace);
        if (!matcher.find()) {
            return null;
        }
        String fqcn = matcher.group(1);
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
    }
}
