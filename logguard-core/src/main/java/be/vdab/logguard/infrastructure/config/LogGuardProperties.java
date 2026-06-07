package be.vdab.logguard.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "logguard")
@Validated
public record LogGuardProperties(
        @NotNull Duration pollInterval,
        @NotNull Duration deduplicationWindow,
        @Min(1) int maxDeliveryAttempts,
        @Min(1) int maxConsecutivePollFailures,
        @NotEmpty List<String> ownCodePackagePrefixes,
        @NotNull OpenSearchProperties openSearch,
        @NotNull GeminiProperties gemini,
        GoogleChatProperties googleChat,
        List<GitLabRepositoryProperties> gitlabRepositories
) {
    public record OpenSearchProperties(
            @NotBlank String uri,
            @NotBlank String indexName
    ) {}

    public record GeminiProperties(
            @NotBlank String apiKey,
            @NotBlank String model
    ) {}

    public record GoogleChatProperties(String webhookUrl) {}

    public record GitLabRepositoryProperties(
            @NotBlank String appName,
            @NotBlank String repoUrl,
            @NotBlank String sourceBranch
    ) {}
}
