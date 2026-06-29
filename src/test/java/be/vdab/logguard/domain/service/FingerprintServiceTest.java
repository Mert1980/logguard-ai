package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.ErrorFingerprint;
import be.vdab.logguard.domain.model.ErrorLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-6 / FR-7: structural fingerprint with the throwing_method (line KEPT) vs stack_trace_sequence
 * (lines STRIPPED) asymmetry, own-code detection, framework-frame fallback, and a stable short hash.
 */
class FingerprintServiceTest {

    private final FingerprintService service = new FingerprintService(List.of("be.vdab"));

    /** Build an ErrorLog carrying only the two fields the service reads. */
    private static ErrorLog errorWith(String exceptionType, String stackTrace) {
        return new ErrorLog(exceptionType, "msg", stackTrace, "svc", "app", "team", "env",
                Instant.parse("2026-06-29T10:00:00Z"), "cn=x");
    }

    @Test
    void normalOwnCodePath_keepsLineInThrowingMethod_stripsLinesInSequence() {
        String trace = """
                java.lang.NullPointerException: boom
                	at be.vdab.app.LabelService.forwardingSourceFor(LabelService.java:27)
                	at be.vdab.app.LabelController.get(LabelController.java:14)
                	at org.springframework.web.method.Invocable.invoke(Invocable.java:255)
                	at java.base/java.lang.Thread.run(Thread.java:840)""";

        ErrorFingerprint fp = service.compute(errorWith("java.lang.NullPointerException", trace));

        assertEquals("java.lang.NullPointerException", fp.exceptionType());
        // First own-code frame, line KEPT:
        assertEquals("LabelService.forwardingSourceFor:27", fp.throwingMethod());
        // Only own-code frames, in order, line numbers STRIPPED (Spring/JDK frames excluded):
        assertEquals(
                "be.vdab.app.LabelService.forwardingSourceFor\nbe.vdab.app.LabelController.get",
                fp.stackTraceSequence());
    }

    @Test
    void asymmetry_nonThrowingFrameLineShift_sameSequenceAndHash_sameThrowingMethod() {
        // Identical call path and identical throwing-frame line; only a NON-throwing own-code frame shifted.
        String traceA = """
                java.lang.NullPointerException: boom
                	at be.vdab.app.LabelService.forwardingSourceFor(LabelService.java:27)
                	at be.vdab.app.LabelController.get(LabelController.java:14)""";
        String traceB = """
                java.lang.NullPointerException: boom
                	at be.vdab.app.LabelService.forwardingSourceFor(LabelService.java:27)
                	at be.vdab.app.LabelController.get(LabelController.java:99)""";

        ErrorFingerprint a = service.compute(errorWith("java.lang.NullPointerException", traceA));
        ErrorFingerprint b = service.compute(errorWith("java.lang.NullPointerException", traceB));

        // Sequence strips lines → identical; throwing-frame line unchanged → identical fingerprint.
        assertEquals(a.stackTraceSequence(), b.stackTraceSequence());
        assertEquals(a.throwingMethod(), b.throwingMethod());
        assertEquals(a.hash(), b.hash());
    }

    @Test
    void asymmetry_throwingFrameLineShift_sameSequence_differentThrowingMethodAndHash() {
        // Same call path, but the THROWING frame's own line differs (27 vs 30) → a different bug.
        String traceAt27 = """
                java.lang.NullPointerException: boom
                	at be.vdab.app.LabelService.forwardingSourceFor(LabelService.java:27)
                	at be.vdab.app.LabelController.get(LabelController.java:14)""";
        String traceAt30 = """
                java.lang.NullPointerException: boom
                	at be.vdab.app.LabelService.forwardingSourceFor(LabelService.java:30)
                	at be.vdab.app.LabelController.get(LabelController.java:14)""";

        ErrorFingerprint at27 = service.compute(errorWith("java.lang.NullPointerException", traceAt27));
        ErrorFingerprint at30 = service.compute(errorWith("java.lang.NullPointerException", traceAt30));

        assertEquals(at27.stackTraceSequence(), at30.stackTraceSequence());     // lines stripped → equal
        assertNotEquals(at27.throwingMethod(), at30.throwingMethod());          // :27 vs :30
        assertNotEquals(at27.hash(), at30.hash());                              // therefore distinct bugs
    }

    @Test
    void frameworkFallback_usesTopmostFrame_whenNoOwnCodeFrame() {
        String trace = """
                org.springframework.beans.factory.BeanCreationException: nope
                	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:333)
                	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:100)""";

        ErrorFingerprint fp = service.compute(
                errorWith("org.springframework.beans.factory.BeanCreationException", trace));

        assertEquals("AbstractBeanFactory.doGetBean:333", fp.throwingMethod());
        assertEquals("org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean",
                fp.stackTraceSequence());
    }

    @Test
    void frameworkFallback_distinctTopmostFrames_produceDistinctFingerprints() {
        String hikariPool = """
                java.sql.SQLException: pool exhausted
                	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:50)""";
        String beanFactory = """
                java.sql.SQLException: pool exhausted
                	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:333)""";

        ErrorFingerprint a = service.compute(errorWith("java.sql.SQLException", hikariPool));
        ErrorFingerprint b = service.compute(errorWith("java.sql.SQLException", beanFactory));

        assertNotEquals(a.hash(), b.hash());
    }

    @Test
    void multiPrefixMatching_recognisesSecondPrefixAsOwnCode() {
        FingerprintService multi = new FingerprintService(List.of("be.vdab", "com.acme"));
        String trace = """
                java.lang.IllegalStateException: x
                	at com.acme.Foo.bar(Foo.java:5)
                	at java.base/java.lang.Thread.run(Thread.java:840)""";

        ErrorFingerprint fp = multi.compute(errorWith("java.lang.IllegalStateException", trace));

        assertEquals("Foo.bar:5", fp.throwingMethod());
        assertEquals("com.acme.Foo.bar", fp.stackTraceSequence());
    }

    @Test
    void nativeFrame_withoutLineNumber_omitsLineSuffix() {
        String trace = """
                java.lang.RuntimeException: native
                	at be.vdab.app.Native.call(Native Method)""";

        ErrorFingerprint fp = service.compute(errorWith("java.lang.RuntimeException", trace));

        assertEquals("Native.call", fp.throwingMethod());   // no ":line"
        assertEquals("be.vdab.app.Native.call", fp.stackTraceSequence());
    }

    @Test
    void hashIsStableAndEightLowercaseHexChars() {
        ErrorFingerprint fp = new ErrorFingerprint("java.lang.NullPointerException",
                "LabelService.forwardingSourceFor:27", "be.vdab.app.LabelService.forwardingSourceFor");

        String first = fp.hash();
        assertEquals(8, first.length());
        assertTrue(first.matches("[0-9a-f]{8}"), "expected 8 lowercase hex chars, got: " + first);
        assertEquals(first, fp.hash());   // deterministic

        ErrorFingerprint different = new ErrorFingerprint("java.lang.NullPointerException",
                "LabelService.forwardingSourceFor:28", "be.vdab.app.LabelService.forwardingSourceFor");
        assertNotEquals(first, different.hash());
    }

    @Test
    void nullStackTrace_fallsBackToExceptionType_emptySequence_noThrow() {
        ErrorFingerprint fp = service.compute(errorWith("java.lang.NullPointerException", null));

        assertNotNull(fp);
        assertEquals("java.lang.NullPointerException", fp.throwingMethod());
        assertEquals("", fp.stackTraceSequence());
    }

    @Test
    void blankStackTrace_fallsBackToExceptionType_emptySequence() {
        ErrorFingerprint fp = service.compute(errorWith("java.lang.NullPointerException", "   \n  "));

        assertEquals("java.lang.NullPointerException", fp.throwingMethod());
        assertEquals("", fp.stackTraceSequence());
    }

    @Test
    void blankExceptionTypeAndNoFrames_fallsBackToUnknown() {
        ErrorFingerprint fp = service.compute(errorWith("", null));

        assertEquals("unknown", fp.throwingMethod());
        assertEquals("", fp.stackTraceSequence());
    }
}
