package be.vdab.logguard.infrastructure.terminal;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The only class permitted to write to {@code System.out} (AR-10). Prints a per-service error block
 * (FR-27/28/30 shape). Root-cause analysis is "analysis unavailable" until the LLM adapter (Epic 3).
 */
@Component
public class TerminalOutputAdapter implements TerminalOutputPort {

    @Override
    public void printServiceErrors(String serviceName, List<ErrorLog> errors) {
        int total = errors.size();
        System.out.println();
        System.out.println("Analyzing " + total + " new error" + (total == 1 ? "" : "s") + " in " + serviceName + "...");
        System.out.println("── " + serviceName + " (" + total + " error" + (total == 1 ? "" : "s")
                + ") ────────────────");
        int index = 1;
        for (ErrorLog error : errors) {
            System.out.println("[" + index + "/" + total + "] " + error.exceptionType());
            System.out.println("  Message:          " + oneLine(error.errorMessage()));
            System.out.println("  Occurred at:      " + error.occurredAt());
            System.out.println("  Root cause:       analysis unavailable (LLM integration is Epic 3)");
            index++;
        }
        System.out.flush();
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "-";
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 200 ? collapsed.substring(0, 197) + "..." : collapsed;
    }
}
