package be.vdab.logguard.infrastructure.googlechat;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The client is a resilient boundary (like {@code LlmAdapter}, FR-24): a transport failure must be
 * swallowed so a Google Chat outage never breaks the poll cycle. Points at a refused loopback port with a
 * short connect timeout and asserts {@link GoogleChatClient#send(String)} returns normally.
 */
class GoogleChatClientTest {

    @Test
    void send_swallowsTransportFailure() {
        HttpClient failing = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(200))
                .build();
        // Port 1 on loopback refuses immediately — deterministic, no external dependency.
        GoogleChatClient client = new GoogleChatClient(failing, "http://127.0.0.1:1/chat", Duration.ofMillis(200));

        assertDoesNotThrow(() -> client.send("hello"));
    }
}
