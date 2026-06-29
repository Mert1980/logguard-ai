package be.vdab.logguard.infrastructure.persistence;

import be.vdab.logguard.domain.model.LLMAnalysis;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists the cached {@link LLMAnalysis} (FR-25) as a JSON string in the {@code stored_analysis} CLOB.
 * Uses Boot 4's primary Jackson 3 ({@code tools.jackson}) — the compile-scoped mapper; the Jackson 2 that
 * {@code opensearch-java} drags in is runtime-only. {@code LLMAnalysis} is a record, bound natively.
 */
@Converter
public class LlmAnalysisJsonConverter implements AttributeConverter<LLMAnalysis, String> {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisJsonConverter.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(LLMAnalysis attribute) {
        return attribute == null ? null : MAPPER.writeValueAsString(attribute);
    }

    @Override
    public LLMAnalysis convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, LLMAnalysis.class);
        } catch (RuntimeException e) {
            // Corrupt or legacy-shaped JSON must not abort the poll cycle — treat as no cached analysis.
            log.warn("Ignoring unreadable stored_analysis JSON: {}", e.getMessage());
            return null;
        }
    }
}
