package be.vdab.logguard.infrastructure.googlechat;

import be.vdab.logguard.infrastructure.config.LogguardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Posts a single plain-text message to a Google Chat incoming webhook as the
 * {@code {"text": "..."}} payload. Uses the JDK {@link HttpClient} (no new dependency) and Boot 4's
 * primary Jackson 3 mapper ({@code tools.jackson}) so the message body is always correctly escaped.
 *
 * <p>Like {@code LlmAdapter} (FR-24), this is a resilient boundary: any transport/HTTP failure is logged
 * and swallowed — a Google Chat outage must never break a poll cycle or reach the domain.</p>
 */
@Component
@ConditionalOnProperty(name = "logguard.google-chat.enabled", havingValue = "true")
public class GoogleChatClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatClient.class);

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final URI webhookUri;
    private final Duration timeout;

    @Autowired
    public GoogleChatClient(LogguardProperties properties) {
        LogguardProperties.GoogleChat config = properties.googleChat();
        if (config.webhookUrl() == null || config.webhookUrl().isBlank()) {
            throw new IllegalStateException(
                    "logguard.google-chat.enabled=true but logguard.google-chat.webhook-url is blank");
        }
        this.webhookUri = URI.create(config.webhookUrl());
        this.timeout = config.timeout();
        this.httpClient = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    /** Package-private — lets tests inject a stub {@link HttpClient} without hitting the network. */
    GoogleChatClient(HttpClient httpClient, String webhookUrl, Duration timeout) {
        this.httpClient = httpClient;
        this.webhookUri = URI.create(webhookUrl);
        this.timeout = timeout;
    }

    /**
     * Send one message. Never throws: a non-2xx status or any transport exception is logged at WARN and
     * discarded so the caller's poll cycle continues unaffected.
     */
    public void send(String text) {
        try {
            String body = jsonMapper.writeValueAsString(Map.of("text", text));
            HttpRequest request = HttpRequest.newBuilder(webhookUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Google Chat rejected message (HTTP {}): {}", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Google Chat send interrupted");
        } catch (Exception e) {
            log.warn("Google Chat send failed: {}", e.getMessage());
        }
    }
}
