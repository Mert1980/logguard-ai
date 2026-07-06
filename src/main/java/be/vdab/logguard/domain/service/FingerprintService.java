package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.ErrorFingerprint;
import be.vdab.logguard.domain.model.ErrorLog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Computes the structural {@link ErrorFingerprint} of an {@link ErrorLog} (FR-6, FR-7). Pure domain —
 * no Spring annotations; the own-code package prefixes are passed in via the constructor (never read
 * from {@code LogguardProperties} inside the domain). Never throws: a null/blank stack trace yields a
 * fingerprint that falls back to the exception type.
 *
 * <p>Frame parsing mirrors the corrected own-code selector in {@code LlmAdapter} (Story 3.2): the FQCN
 * is parsed from the frame, not substring-matched, so a package prefix appearing in a message cannot
 * false-match. The regex here additionally captures the method and source location to derive line numbers.
 */
public class FingerprintService {

    /**
     * Matches a stack frame line {@code at [<module>/]<fqcn>.<method>(<location>)}.
     * Group 1 = FQCN, group 2 = method, group 3 = source location
     * ({@code File.java:27} | {@code Native Method} | {@code Unknown Source}).
     *
     * <p>The optional {@code (?:[\w$.]+/)?} prefix consumes the Java 9+ module segment a JVM renders before
     * the FQCN (e.g. {@code java.base/}java.lang.Thread.run, or {@code <appmodule>/}be.vdab.x.Foo.bar) so
     * those frames parse instead of being silently dropped — otherwise a framework-only trace whose frames
     * are all module-prefixed would collapse to a degenerate fingerprint (FR-7 / AC#5).</p>
     */
    private static final Pattern FRAME =
            Pattern.compile("^\\s*at\\s+(?:[\\w$.]+/)?([\\w$.]+)\\.([\\w$<>]+)\\(([^)]*)\\)");

    private final List<String> ownCodePrefixes;

    public FingerprintService(List<String> ownCodePackagePrefixes) {
        this.ownCodePrefixes = ownCodePackagePrefixes == null
                ? List.of()
                : ownCodePackagePrefixes.stream()
                        .filter(p -> p != null && !p.isBlank())
                        .toList();
    }

    public ErrorFingerprint compute(ErrorLog error) {
        String exceptionType = error.exceptionType();
        List<Frame> frames = parseFrames(error.stackTrace());
        List<Frame> ownCode = frames.stream().filter(this::isOwnCode).toList();

        Frame throwingFrame;
        List<Frame> sequenceFrames;
        if (!ownCode.isEmpty()) {
            // Normal path: first own-code frame is the throw site; the sequence is all own-code frames.
            throwingFrame = ownCode.get(0);
            sequenceFrames = ownCode;
        } else if (!frames.isEmpty()) {
            // Framework-frame fallback (FR-7): no own-code frame (Spring startup, Hikari, static 404s) —
            // use the topmost frame so distinct framework-only errors do not collapse into one fingerprint.
            throwingFrame = frames.get(0);
            sequenceFrames = List.of(frames.get(0));
        } else {
            // Degenerate trace (null/blank/unparseable): no frame to anchor on.
            throwingFrame = null;
            sequenceFrames = List.of();
        }

        String throwingMethod = throwingFrame != null
                ? throwingFrame.throwingMethod()   // line number KEPT
                : orUnknown(exceptionType);
        String stackTraceSequence = sequenceFrames.stream()
                .map(Frame::normalized)            // line numbers STRIPPED
                .collect(Collectors.joining("\n"));

        return new ErrorFingerprint(exceptionType, throwingMethod, stackTraceSequence);
    }

    private boolean isOwnCode(Frame frame) {
        return ownCodePrefixes.stream()
                .anyMatch(p -> frame.fqcn().equals(p) || frame.fqcn().startsWith(p + "."));
    }

    private static List<Frame> parseFrames(String stackTrace) {
        List<Frame> frames = new ArrayList<>();
        if (stackTrace == null || stackTrace.isBlank()) {
            return frames;
        }
        for (String line : stackTrace.split("\\R")) {
            Matcher matcher = FRAME.matcher(line);
            if (matcher.find()) {
                frames.add(new Frame(matcher.group(1), matcher.group(2), parseLineNumber(matcher.group(3))));
            }
        }
        return frames;
    }

    /** The integer after the last {@code :} in the location, or {@code null} for native/unknown sources. */
    private static Integer parseLineNumber(String location) {
        int colon = location.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        try {
            return Integer.valueOf(location.substring(colon + 1).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String orUnknown(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }

    /** A single parsed stack frame. {@code line} is null when the source has no line number. */
    private record Frame(String fqcn, String method, Integer line) {

        /** {@code SimpleClass.method:line} — the line number is KEPT (omitted only if unknown). */
        String throwingMethod() {
            String base = simpleClass() + "." + method;
            return line == null ? base : base + ":" + line;
        }

        /** {@code fqcn.method} — line number STRIPPED for the deploy-stable sequence. */
        String normalized() {
            return fqcn + "." + method;
        }

        private String simpleClass() {
            int dot = fqcn.lastIndexOf('.');
            return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
        }
    }
}
