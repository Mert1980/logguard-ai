package be.vdab.logguard.infrastructure.adapter.out.notification;

import be.vdab.logguard.domain.model.GitLabLink;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.model.Notification;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/** Builds the human-readable message body used by both the Google Chat and console adapters. */
@Component
public class NotificationMessageFormatter {

    public String formatText(Notification notification) {
        var el = notification.getErrorLog();
        var sb = new StringBuilder();

        sb.append("*🚨 Error Alert — ").append(el.appName()).append("*\n");
        sb.append("*Exception:* `").append(el.exceptionType()).append("`\n");
        sb.append("*Message:*   ").append(shorten(el.errorMessage(), 200)).append("\n");
        sb.append("*Service:*   ").append(el.serviceName())
          .append("  |  *Team:* ").append(el.team())
          .append("  |  *Env:* ").append(el.environment()).append("\n");

        switch (notification.getAnalysis()) {
            case LLMAnalysis.Available a -> {
                sb.append("\n*📋 Summary:*\n").append(a.summary()).append("\n");
                sb.append("\n*🔍 Root Cause:*\n").append(a.rootCause()).append("\n");
                sb.append("\n*🔧 Suggested Fix:*\n").append(a.suggestedFix()).append("\n");
            }
            case LLMAnalysis.Unavailable u ->
                sb.append("\n⚠️  _AI analysis unavailable: ").append(u.reason()).append("_\n");
        }

        if (!notification.getGitLabLinks().isEmpty()) {
            sb.append("\n*🔗 Source Links:*\n");
            notification.getGitLabLinks().stream()
                    .limit(5)
                    .forEach(link -> sb.append("  • `")
                            .append(simpleClassName(link.className())).append("` line ")
                            .append(link.lineNumber()).append(" — ").append(link.url()).append("\n"));
        }

        return sb.toString();
    }

    public String formatDegradationAlert(int consecutiveFailures) {
        return "*🔴 OpenSearch Degradation Alert*\n" +
               "OpenSearch has been unreachable for *" + consecutiveFailures + "* consecutive poll cycles.\n" +
               "Error catch-up is paused until connectivity is restored.";
    }

    private String shorten(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
