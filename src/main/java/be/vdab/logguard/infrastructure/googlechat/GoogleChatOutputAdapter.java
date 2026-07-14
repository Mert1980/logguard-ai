package be.vdab.logguard.infrastructure.googlechat;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Delivers every notification to Google Chat instead of {@code System.out}. Selected as the {@link Primary}
 * {@link TerminalOutputPort} whenever {@code logguard.google-chat.enabled=true}; when disabled this bean is
 * absent and {@code TerminalOutputAdapter} remains the sole implementation.
 *
 * <p>Each port method is rendered as one chat message using Google Chat's basic text formatting
 * ({@code *bold*}, {@code `code`}) and the same {@code 🔴/🟢/⚑/⚠️} status glyphs as the terminal output, then
 * handed to {@link GoogleChatClient#send(String)} — which is itself resilient (a delivery failure never
 * propagates into the poll cycle).</p>
 */
@Component
@Primary
@ConditionalOnProperty(name = "logguard.google-chat.enabled", havingValue = "true")
public class GoogleChatOutputAdapter implements TerminalOutputPort {

    /** Matches the topmost stack frame {@code at <fqcn>.<method>(...)} — group 1 is the FQCN. */
    private static final Pattern FIRST_FRAME = Pattern.compile("(?m)^\\s*at\\s+([\\w$.]+)\\.[\\w$<>]+\\(");

    /** Cap the unavailability reason so a long/multi-line message stays a single readable line. */
    private static final int MAX_REASON_LENGTH = 100;

    // FR-31/32/34/35 status indicators — the same glyphs the terminal adapter uses.
    static final String DEGRADED = "🔴";
    static final String RECOVERED = "🟢";
    static final String WONT_FIX = "⚑";
    static final String ESCALATION = "⚠️";

    private final GoogleChatClient client;

    public GoogleChatOutputAdapter(GoogleChatClient client) {
        this.client = client;
    }

    @Override
    public void printProgress(String serviceName, int count) {
        String plural = count == 1 ? "" : "s";
        client.send("*Analyzing " + count + " new error" + plural + " in " + serviceName + "*");
    }

    @Override
    public void printAnalysis(int index, int total, ErrorLog error, LLMAnalysis analysis,
                              String humanLabel, String hash) {
        StringBuilder message = new StringBuilder();
        message.append("*[").append(index).append('/').append(total).append("] ")
                .append(header(error)).append("*\n");
        if (analysis.llmAvailable()) {
            message.append("• *Root cause:* ").append(dash(analysis.rootCause())).append('\n');
            message.append("• *Likely location:* ").append(dash(analysis.likelyLocation())).append('\n');
            message.append("• *Suggested action:* ").append(dash(analysis.suggestedAction())).append('\n');
        } else {
            message.append("• *Root cause:* analysis unavailable (")
                    .append(reason(analysis.unavailabilityReason())).append(")\n");
        }
        // FR-18/FR-30: every new-error block ends with the copy-pasteable fingerprint (bracketed hash).
        message.append("Fingerprint: ").append(humanLabel).append("  `").append(hash).append('`');
        client.send(message.toString());
    }

    @Override
    public void printWontFixLabel(String humanLabel, String hash) {
        // FR-17/FR-32: unbracketed hash (matches the suppression-file line format `hash  # HumanLabel`).
        client.send(WONT_FIX + " *Known / Won't Fix:* " + humanLabel + "  `" + hash + "`");
    }

    @Override
    public void printDegraded(Instant degradationStartedAt, int consecutiveFailures) {
        String plural = consecutiveFailures == 1 ? "" : "s";
        client.send(DEGRADED + " *LOGGUARD DEGRADED* — OpenSearch unreachable since "
                + degradationStartedAt + " (" + consecutiveFailures + " failure" + plural + ")");
    }

    @Override
    public void printRecovery(Instant resumedAt) {
        client.send(RECOVERED + " *LOGGUARD RECOVERED* — polling resumed at " + resumedAt
                + ", catching up from checkpoint");
    }

    @Override
    public void printSuppressionUnreadable() {
        client.send(ESCALATION + " *Suppression file unreadable* — using last known state");
    }

    @Override
    public void printEscalation(String humanLabel, int threshold, Instant firstSeen, LLMAnalysis stored,
                                boolean wontFix) {
        // FR-11/FR-12/FR-31: re-notification reusing the cached analysis (no fresh LLM call). Two shapes.
        if (wontFix) {
            // Volume override (FR-12): one line, no analysis, a read-only nudge — wontFix is NOT cleared.
            client.send(ESCALATION + " *Won't-fix error* " + humanLabel + " now seen " + threshold
                    + "× since " + firstSeen + " — volume is unusually high");
        } else {
            // Cooling re-notification (FR-31): reuses the stored root cause.
            client.send(ESCALATION + " *Known error* " + humanLabel + " now seen " + threshold
                    + "× since " + firstSeen + "\nRoot cause: " + storedRootCause(stored));
        }
    }

    /** Cached root cause for an escalation, or "no analysis on file" when none was successfully cached (FR-25). */
    private static String storedRootCause(LLMAnalysis stored) {
        if (stored == null || !stored.llmAvailable() || stored.rootCause() == null || stored.rootCause().isBlank()) {
            return "no analysis on file";
        }
        return stored.rootCause().strip();
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
        String exceptionType = dash(error.exceptionType());
        String throwingClass = throwingClass(error.stackTrace());
        return throwingClass == null ? exceptionType : exceptionType + "@" + throwingClass;
    }

    /** Simple class name of the topmost stack frame (the throw site), or {@code null} if undetectable. */
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
