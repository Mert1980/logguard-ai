package be.vdab.logguard.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Structural identity of an error (FR-6). Pure domain value object — no Spring/JPA annotations.
 *
 * <p>The two stack-trace fields carry <strong>opposite</strong> line-number policies, by design:
 * <ul>
 *   <li>{@code throwingMethod} KEEPS the line number — it distinguishes two different bugs in the same
 *       method (e.g. {@code LabelV2Config.getForwardingSource:21}).</li>
 *   <li>{@code stackTraceSequence} STRIPS all line numbers — it lets the fingerprint survive deploys that
 *       shift call-path lines without changing the bug.</li>
 * </ul>
 * Do NOT normalise both the same way: stripping the line from {@code throwingMethod} would collapse
 * distinct bugs into one fingerprint.
 *
 * @param exceptionType      the throwable type (copied verbatim from the {@link ErrorLog})
 * @param throwingMethod     {@code SimpleClass.method:line} of the first own-code frame (line KEPT);
 *                           the topmost frame as fallback when no own-code frame exists (FR-7)
 * @param stackTraceSequence newline-joined {@code fqcn.method} of all own-code frames, line numbers STRIPPED
 *                           (the single topmost frame as fallback)
 */
public record ErrorFingerprint(
        String exceptionType,
        String throwingMethod,
        String stackTraceSequence
) {

    /**
     * Stable short identity hash over the three components: SHA-256 of
     * {@code exceptionType\nthrowingMethod\nstackTraceSequence} (UTF-8), rendered lowercase hex and
     * truncated to the first 8 chars. Deterministic across JVM restarts — it is the lookup key for the
     * deduplication record (Story 4.2) and the value matched against the suppression file (Story 4.3),
     * and appears in the copy-pasteable Fingerprint output line (FR-18).
     */
    public String hash() {
        String canonical = nullToEmpty(exceptionType) + '\n'
                + nullToEmpty(throwingMethod) + '\n'
                + nullToEmpty(stackTraceSequence);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM but was not available", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
