package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.ErrorFingerprint;
import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.StackFrame;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Implements the spec's black-box functions first_own_code_frame and strip_tenant_data.
 * Pure computation — no I/O, no side effects.
 */
@Component
public class FingerprintComputer {

    public ErrorFingerprint compute(ErrorLog errorLog, List<String> packagePrefixes) {
        String throwingMethod = extractFirstOwnCodeFrame(errorLog.stackTrace(), packagePrefixes);
        String strippedTrace  = stripTenantData(errorLog.stackTrace());
        return new ErrorFingerprint(errorLog.exceptionType(), throwingMethod, strippedTrace);
    }

    public List<StackFrame> parseOwnCodeFrames(String stackTrace, List<String> packagePrefixes) {
        return Arrays.stream(stackTrace.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("at "))
                .map(line -> line.substring(3))
                .filter(frame -> packagePrefixes.stream().anyMatch(frame::startsWith))
                .map(this::parseFrame)
                .flatMap(Optional::stream)
                .toList();
    }

    private String extractFirstOwnCodeFrame(String stackTrace, List<String> packagePrefixes) {
        return Arrays.stream(stackTrace.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("at "))
                .map(line -> line.substring(3))
                .filter(frame -> packagePrefixes.stream().anyMatch(frame::startsWith))
                .findFirst()
                .map(frame -> {
                    int paren = frame.indexOf('(');
                    return paren > 0 ? frame.substring(0, paren) : frame;
                })
                .orElse("unknown");
    }

    private Optional<StackFrame> parseFrame(String frameStr) {
        // frameStr: "com.vdab.MyClass.myMethod(MyClass.java:42)"
        try {
            int parenOpen  = frameStr.lastIndexOf('(');
            int parenClose = frameStr.lastIndexOf(')');
            if (parenOpen < 0 || parenClose < 0) return Optional.empty();

            String classAndMethod = frameStr.substring(0, parenOpen);
            String fileAndLine    = frameStr.substring(parenOpen + 1, parenClose);

            int lastDot   = classAndMethod.lastIndexOf('.');
            String className  = classAndMethod.substring(0, lastDot);
            String methodName = classAndMethod.substring(lastDot + 1);

            int colon      = fileAndLine.lastIndexOf(':');
            int lineNumber = (colon >= 0) ? Integer.parseInt(fileAndLine.substring(colon + 1)) : 0;

            return Optional.of(new StackFrame(className, methodName, lineNumber));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Strips tenant-specific identifiers so the same error for different tenants
     * produces the same fingerprint, per the spec.
     */
    String stripTenantData(String stackTrace) {
        return stackTrace
                // Belgian enterprise numbers (KBO): 10 digits, optionally formatted as 0xxx.xxx.xxx
                .replaceAll("\\b0\\d{3}\\.\\d{3}\\.\\d{3}\\b", "KBO_REDACTED")
                .replaceAll("\\b\\d{10}\\b", "KBO_REDACTED")
                // UUIDs
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                        "UUID_REDACTED")
                // Numeric IDs that appear after common prefixes (id=, Id:, /users/123)
                .replaceAll("(?<=[=:/])\\d{4,}", "ID_REDACTED");
    }
}
