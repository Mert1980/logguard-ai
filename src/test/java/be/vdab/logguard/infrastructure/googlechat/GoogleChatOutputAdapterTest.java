package be.vdab.logguard.infrastructure.googlechat;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Google Chat rendering of each {@code TerminalOutputPort} event: the per-error block carries
 * the FR-18/FR-30 {@code Fingerprint} line (bracketed hash), the won't-fix label keeps the hash unbracketed
 * (FR-32), and the two escalation shapes (cooling vs. volume override) match FR-11/FR-12. Uses a capturing
 * stub client so no HTTP happens.
 */
class GoogleChatOutputAdapterTest {

    /** Captures every message the adapter would POST, without touching the network. */
    private static final class CapturingClient extends GoogleChatClient {
        final List<String> sent = new ArrayList<>();

        CapturingClient() {
            super(null, "http://localhost", Duration.ofSeconds(1));
        }

        @Override
        public void send(String text) {
            sent.add(text);
        }
    }

    private final CapturingClient client = new CapturingClient();
    private final GoogleChatOutputAdapter adapter = new GoogleChatOutputAdapter(client);

    @Test
    void printProgress_pluralisesAndSendsOneMessage() {
        adapter.printProgress("orders", 3);

        assertEquals(1, client.sent.size());
        assertEquals("*Analyzing 3 new errors in orders*", client.sent.getFirst());
    }

    @Test
    void printAnalysis_available_endsWithBracketedFingerprintLine() {
        ErrorLog error = errorWith("java.lang.NullPointerException");
        LLMAnalysis analysis = LLMAnalysis.available("npe on null label", "Foo.bar:7", "guard the lookup");

        adapter.printAnalysis(1, 1, error, analysis, "NullPointerException@Foo:7", "a1b2c3d4");

        String message = client.sent.getFirst();
        assertTrue(message.contains("Root cause:"), message);
        assertTrue(message.contains("npe on null label"), message);
        assertTrue(message.endsWith("Fingerprint: NullPointerException@Foo:7  `a1b2c3d4`"),
                "block ends with the bracketed fingerprint; got:\n" + message);
    }

    @Test
    void printAnalysis_unavailable_stillCarriesFingerprintLine() {
        ErrorLog error = errorWith("java.lang.NullPointerException");
        LLMAnalysis analysis = LLMAnalysis.unavailable("ollama unreachable");

        adapter.printAnalysis(1, 1, error, analysis, "NullPointerException@Foo:7", "a1b2c3d4");

        String message = client.sent.getFirst();
        assertTrue(message.contains("analysis unavailable (ollama unreachable)"), message);
        assertTrue(message.contains("Fingerprint: NullPointerException@Foo:7  `a1b2c3d4`"),
                "FR-18: a failed analysis still carries the Fingerprint line; got:\n" + message);
    }

    @Test
    void printWontFixLabel_hashIsUnbracketed() {
        adapter.printWontFixLabel("NullPointerException@Foo:7", "a1b2c3d4");

        String message = client.sent.getFirst();
        assertTrue(message.contains("⚑ *Known / Won't Fix:* NullPointerException@Foo:7  `a1b2c3d4`"), message);
        assertFalse(message.contains("[a1b2c3d4]"), "the WontFix label must NOT bracket the hash (FR-32)");
    }

    @Test
    void printEscalation_cooling_reusesStoredRootCause() {
        LLMAnalysis stored = LLMAnalysis.available("connection pool exhausted", "Foo.bar:7", "raise max-pool");
        Instant firstSeen = Instant.parse("2026-06-15T00:00:00Z");

        adapter.printEscalation("NullPointerException@Foo:7", 100, firstSeen, stored, false);

        String message = client.sent.getFirst();
        assertTrue(message.contains("⚠️ *Known error* NullPointerException@Foo:7 now seen 100× since " + firstSeen),
                message);
        assertTrue(message.contains("Root cause: connection pool exhausted"), message);
    }

    @Test
    void printEscalation_wontFix_isOneLineVolumeOverrideWithoutRootCause() {
        Instant firstSeen = Instant.parse("2026-06-15T00:00:00Z");

        adapter.printEscalation("IllegalStateException@Bar:9", 1000, firstSeen, null, true);

        String message = client.sent.getFirst();
        assertTrue(message.contains("⚠️ *Won't-fix error* IllegalStateException@Bar:9 now seen 1000× since "
                + firstSeen + " — volume is unusually high"), message);
        assertFalse(message.contains("Root cause:"), "the volume override carries no Root cause line; got:\n" + message);
    }

    private static ErrorLog errorWith(String exceptionType) {
        return new ErrorLog(exceptionType, "boom",
                "java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)",
                "svc", "app", "team", "local", Instant.parse("2026-06-15T00:00:00Z"), "cn=X");
    }
}
