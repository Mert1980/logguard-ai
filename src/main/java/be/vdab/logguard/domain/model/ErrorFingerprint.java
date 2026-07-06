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

    /**
     * Human-readable label derived purely from this fingerprint (FR-18 / FR-32), used both as the final
     * {@code Fingerprint:} line of every new-error block and as the {@code ⚑ Known / Won't Fix} label.
     * Format {@code {SimpleExceptionName}@{SimpleClass}:{line}} — e.g. a {@code NullPointerException} thrown
     * at {@code be.vdab.label.LabelV2Config.getForwardingSource:21} → {@code NullPointerException@LabelV2Config:21}.
     *
     * <p>The label is COMPUTED from the fingerprint, not read from the suppression-file comment: the Story 4.3
     * port is frozen to {@code Set<String> loadHashes()} (comments are parsed away), and the hash is the real
     * key — the label is only a recognisable cue. Degenerate fingerprints (no parseable own-code/framework
     * frame, so {@code throwingMethod} is blank, equals the exception type, or is the {@code "unknown"}
     * fallback) collapse to just the simple exception name with no {@code @class} suffix.</p>
     */
    public String humanLabel() {
        String exception = simpleExceptionName();
        String classAndLine = classWithLine();
        return classAndLine == null ? exception : exception + "@" + classAndLine;
    }

    /** Simple name of {@code exceptionType} (after the last {@code .}); {@code "UnknownError"} when blank or a degenerate type (e.g. a trailing dot) leaves nothing after it. */
    private String simpleExceptionName() {
        String type = exceptionType == null ? "" : exceptionType.strip();
        if (type.isEmpty()) {
            return "UnknownError";
        }
        int lastDot = type.lastIndexOf('.');
        String simple = lastDot >= 0 ? type.substring(lastDot + 1) : type;
        return simple.isEmpty() ? "UnknownError" : simple;
    }

    /**
     * {@code {SimpleClass}:{line}} from {@code throwingMethod} ({@code SimpleClass.method:line}); class is the
     * segment before the first {@code .}, line the segment after the last {@code :} (omitted when absent —
     * e.g. a native frame). Returns {@code null} for degenerate {@code throwingMethod} values so the caller
     * drops the {@code @class} suffix entirely.
     */
    private String classWithLine() {
        String method = throwingMethod == null ? "" : throwingMethod.strip();
        // FingerprintService's degenerate fallback copies exceptionType VERBATIM into throwingMethod, so
        // compare against the stripped exceptionType (not the raw value) — otherwise a whitespace-padded
        // type slips past the guard and gets split as a FQCN, yielding a garbage @package suffix.
        String type = exceptionType == null ? "" : exceptionType.strip();
        if (method.isEmpty() || method.equals("unknown") || method.equals(type)) {
            return null;
        }
        int firstDot = method.indexOf('.');
        String simpleClass = firstDot >= 0 ? method.substring(0, firstDot) : method;
        int lastColon = method.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < method.length() - 1) {
            return simpleClass + ":" + method.substring(lastColon + 1);
        }
        return simpleClass;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
