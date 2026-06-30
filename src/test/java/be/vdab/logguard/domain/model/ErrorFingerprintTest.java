package be.vdab.logguard.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FR-18 / FR-32: the computed {@code humanLabel()} used on the {@code Fingerprint:} line and the
 * {@code ⚑ Known / Won't Fix} label. Per the Story 4.4 decision (METIS, 2026-06-30) the exception is
 * rendered as its FULL simple name (e.g. {@code NullPointerException}), not an acronym.
 */
class ErrorFingerprintTest {

    @Test
    void ownCodeFrame_rendersFullSimpleNameAtClassAndLine() {
        ErrorFingerprint fp = new ErrorFingerprint(
                "java.lang.NullPointerException",
                "LabelV2Config.getForwardingSource:21",
                "be.vdab.label.LabelV2Config.getForwardingSource");

        assertEquals("NullPointerException@LabelV2Config:21", fp.humanLabel());
    }

    @Test
    void frameworkFallbackFrame_stillRendersClassAndLine() {
        // No own-code frame ⇒ FingerprintService falls back to the topmost frame.
        ErrorFingerprint fp = new ErrorFingerprint(
                "org.springframework.beans.factory.BeanCreationException",
                "AbstractBeanFactory.doGetBean:333",
                "org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean");

        assertEquals("BeanCreationException@AbstractBeanFactory:333", fp.humanLabel());
    }

    @Test
    void nativeFrameWithoutLine_omitsLineSuffix() {
        ErrorFingerprint fp = new ErrorFingerprint(
                "java.lang.RuntimeException",
                "Native.call",
                "be.vdab.app.Native.call");

        assertEquals("RuntimeException@Native", fp.humanLabel());
    }

    @Test
    void degenerateTrace_throwingMethodEqualsExceptionType_collapsesToExceptionNameOnly() {
        // FingerprintService's null/blank-stack-trace fallback sets throwingMethod = exceptionType.
        ErrorFingerprint fp = new ErrorFingerprint(
                "java.lang.NullPointerException",
                "java.lang.NullPointerException",
                "");

        assertEquals("NullPointerException", fp.humanLabel());
    }

    @Test
    void degenerateTrace_unknownThrowingMethodAndBlankType_collapsesToUnknownError() {
        // FingerprintService's blank-type + no-frame fallback: throwingMethod = "unknown".
        ErrorFingerprint fp = new ErrorFingerprint("", "unknown", "");

        assertEquals("UnknownError", fp.humanLabel());
    }

    @Test
    void degenerateTrace_whitespacePaddedExceptionType_collapsesToExceptionNameOnly() {
        // FingerprintService's degenerate fallback copies exceptionType VERBATIM into throwingMethod, so a
        // padded type yields padded throwingMethod. The guard must still collapse it (no "@package" suffix).
        ErrorFingerprint fp = new ErrorFingerprint(
                "  com.example.FooException  ",
                "  com.example.FooException  ",
                "");

        assertEquals("FooException", fp.humanLabel());
    }

    @Test
    void degenerateType_trailingDot_fallsBackToUnknownError_noEmptyLabel() {
        // A trailing-dot exception type leaves nothing after the last '.'; must not produce an empty label.
        ErrorFingerprint fp = new ErrorFingerprint("com.example.", "com.example.", "");

        assertEquals("UnknownError", fp.humanLabel());
    }
}
