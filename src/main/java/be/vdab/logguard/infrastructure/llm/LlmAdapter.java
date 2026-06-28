package be.vdab.logguard.infrastructure.llm;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.port.out.LlmPort;
import be.vdab.logguard.domain.service.TenantDataSanitizer;
import be.vdab.logguard.infrastructure.config.LogguardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls the local Ollama LLM (Spring AI {@link ChatClient}) to analyse an error and returns the
 * three-field {@link LLMAnalysis}. Catches everything at this boundary and returns
 * {@code llmAvailable=false} on any failure (FR-24) — infrastructure exceptions never reach the domain.
 *
 * <p>The payload is the FR-20 set: exception type, service, app, triggering LDAP identity, and the
 * tenant-stripped (FR-21) message + own-code stack frames. The system prompt is loaded from
 * {@code prompts/llm-analysis.st} (AR-14); the JSON output contract is added by {@code .entity(...)}.</p>
 */
@Component
public class LlmAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(LlmAdapter.class);
    private static final String PROMPT_RESOURCE = "prompts/llm-analysis.st";

    private final ChatClient chatClient;
    private final TenantDataSanitizer sanitizer = new TenantDataSanitizer();
    private final List<String> ownCodePrefixes;
    private final String systemPrompt;

    @Autowired
    public LlmAdapter(ChatClient.Builder chatClientBuilder, LogguardProperties properties) {
        this(chatClientBuilder.build(), properties.ownCodePackagePrefixes());
    }

    /** Package-private — lets tests inject a ChatClient over a stub/throwing ChatModel. */
    LlmAdapter(ChatClient chatClient, List<String> ownCodePrefixes) {
        this.chatClient = chatClient;
        this.ownCodePrefixes = ownCodePrefixes;
        this.systemPrompt = loadSystemPrompt();
    }

    @Override
    public LLMAnalysis analyse(ErrorLog error) {
        try {
            RootCauseAnalysis result = chatClient.prompt()
                    .system(systemPrompt)
                    .user(buildPayload(error))
                    .call()
                    .entity(RootCauseAnalysis.class);
            return toAnalysis(result);
        } catch (Exception e) {
            log.warn("LLM analysis failed for {}: {}", error.exceptionType(), e.getMessage());
            return LLMAnalysis.unavailable(shortReason(e));
        }
    }

    /** Maps the LLM's three-field reply to the domain result; ANY null/blank field ⇒ malformed (FR-23/24). */
    static LLMAnalysis toAnalysis(RootCauseAnalysis result) {
        if (result == null
                || isBlank(result.rootCause())
                || isBlank(result.likelyLocation())
                || isBlank(result.suggestedAction())) {
            return LLMAnalysis.unavailable("malformed response");
        }
        return LLMAnalysis.available(result.rootCause(), result.likelyLocation(), result.suggestedAction());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String buildPayload(ErrorLog error) {
        return """
                Exception type: %s
                Service: %s
                App: %s
                Triggering identity (LDAP): %s
                Error message: %s
                Stack trace (own-code frames only):
                %s
                """.formatted(
                orUnknown(error.exceptionType()),
                orUnknown(error.serviceName()),
                orUnknown(error.appName()),
                orUnknown(error.vdabAuthorization()),
                orUnknown(sanitizer.sanitize(error.errorMessage())),
                sanitizer.sanitize(ownCodeFrames(error.stackTrace())));
    }

    /** Null/blank field ⇒ "unknown" so the LLM payload never carries the literal string "null". */
    private static String orUnknown(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }

    /** Matches a stack frame line {@code at <fqcn>.<method>(...)}; group 1 is the frame's FQCN. */
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+([\\w$.]+)\\.[\\w$<>]+\\(");

    /** Keep the exception header line plus any frame whose class is in a configured own-code package (FR-20). */
    private String ownCodeFrames(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return "";
        }
        List<String> prefixes = ownCodePrefixes.stream()
                .filter(p -> p != null && !p.isBlank())
                .toList();
        String[] lines = stackTrace.split("\\R");
        StringBuilder kept = new StringBuilder();
        // Always keep the first line (the exception header: throwable type + message).
        kept.append(lines[0].strip()).append('\n');
        // Keep only "at <fqcn>" frames whose class belongs to an own-code package — match the parsed
        // class (not substring-anywhere) so prefixes in messages don't false-match, and start at index 1
        // so the header line is never re-appended.
        for (int i = 1; i < lines.length; i++) {
            Matcher matcher = FRAME.matcher(lines[i]);
            if (matcher.find()) {
                String fqcn = matcher.group(1);
                if (prefixes.stream().anyMatch(p -> fqcn.equals(p) || fqcn.startsWith(p + "."))) {
                    kept.append(lines[i].strip()).append('\n');
                }
            }
        }
        return kept.toString();
    }

    private static String shortReason(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return (message == null || message.isBlank()) ? root.getClass().getSimpleName() : message;
    }

    private static String loadSystemPrompt() {
        try {
            return new String(new ClassPathResource(PROMPT_RESOURCE).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + PROMPT_RESOURCE, e);
        }
    }
}
