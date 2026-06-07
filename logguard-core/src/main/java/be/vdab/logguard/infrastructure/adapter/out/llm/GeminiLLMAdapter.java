package be.vdab.logguard.infrastructure.adapter.out.llm;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.port.out.LLMAnalysisPort;
import be.vdab.logguard.infrastructure.config.LogGuardProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class GeminiLLMAdapter implements LLMAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiLLMAdapter.class);

    private final RestClient restClient;
    private final LogGuardProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiLLMAdapter(RestClient.Builder restClientBuilder,
                            LogGuardProperties properties,
                            ObjectMapper objectMapper) {
        this.restClient = restClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
        this.properties   = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public LLMAnalysis analyse(ErrorLog errorLog) {
        try {
            GeminiRequest request = new GeminiRequest(
                    List.of(new GeminiRequest.Content(List.of(new GeminiRequest.Part(buildPrompt(errorLog))))),
                    new GeminiRequest.GenerationConfig("application/json")
            );

            GeminiResponse response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/{model}:generateContent")
                            .queryParam("key", properties.gemini().apiKey())
                            .build(properties.gemini().model()))
                    .body(request)
                    .retrieve()
                    .body(GeminiResponse.class);

            String text = response.candidates().get(0).content().parts().get(0).text();
            AnalysisJson parsed = objectMapper.readValue(text, AnalysisJson.class);

            return new LLMAnalysis.Available(parsed.summary(), parsed.rootCause(), parsed.suggestedFix());

        } catch (Exception e) {
            log.warn("Gemini analysis failed, sending notification without AI analysis: {}", e.getMessage());
            return new LLMAnalysis.Unavailable(e.getMessage());
        }
    }

    private String buildPrompt(ErrorLog errorLog) {
        return """
                Analyse this Java application error. Respond ONLY with a JSON object — no markdown, no code blocks.
                Use exactly these keys: "summary", "rootCause", "suggestedFix".

                Exception type:  %s
                Error message:   %s
                Service:         %s (%s)
                Team:            %s
                Environment:     %s

                Stack trace:
                %s
                """.formatted(
                errorLog.exceptionType(),
                errorLog.errorMessage(),
                errorLog.serviceName(), errorLog.appName(),
                errorLog.team(),
                errorLog.environment(),
                truncate(errorLog.stackTrace(), 4000)
        );
    }

    private String truncate(String text, int maxLength) {
        return (text != null && text.length() > maxLength)
                ? text.substring(0, maxLength) + "\n... (truncated)"
                : text;
    }

    // --- Internal DTOs for Gemini API ---

    record GeminiRequest(List<Content> contents, GenerationConfig generationConfig) {
        record Content(List<Part> parts) {}
        record Part(String text) {}
        record GenerationConfig(String responseMimeType) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiResponse(List<Candidate> candidates) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Candidate(Content content) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Content(List<Part> parts) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Part(String text) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnalysisJson(String summary, String rootCause, String suggestedFix) {}
}
