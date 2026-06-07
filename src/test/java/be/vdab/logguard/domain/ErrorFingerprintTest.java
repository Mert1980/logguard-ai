package be.vdab.logguard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec: ErrorFingerprint value type
 *
 * Two errors sharing exception_type + throwing_method + normalised stack_trace_sequence
 * are considered the same error, regardless of tenant-specific data (KBO numbers, IDs).
 *
 * Spec rule: "if the same exception class occurs in a different method it is not a duplicate"
 */
@DisplayName("ErrorFingerprint")
class ErrorFingerprintTest {

    // TODO: replace with actual ErrorFingerprint.from(ErrorLog, packagePrefix) once implemented

    private static final String STACK_TRACE_KBO_001 =
        "java.lang.NullPointerException\n" +
        "\tat be.vdab.app.service.UserService.processRequest(UserService.java:42)\n" +
        "\tat be.vdab.app.controller.UserController.handle(UserController.java:18)\n" +
        "Context: KBO=0123456789";

    private static final String STACK_TRACE_KBO_002 =
        "java.lang.NullPointerException\n" +
        "\tat be.vdab.app.service.UserService.processRequest(UserService.java:42)\n" +
        "\tat be.vdab.app.controller.UserController.handle(UserController.java:18)\n" +
        "Context: KBO=9876543210";

    private static final String STACK_TRACE_DIFFERENT_METHOD =
        "java.lang.NullPointerException\n" +
        "\tat be.vdab.app.service.UserService.validate(UserService.java:87)\n" +
        "\tat be.vdab.app.controller.UserController.handle(UserController.java:18)\n";

    private static final String STACK_TRACE_DIFFERENT_EXCEPTION =
        "java.lang.IllegalArgumentException\n" +
        "\tat be.vdab.app.service.UserService.processRequest(UserService.java:42)\n";

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("identical inputs produce equal fingerprints")
        void identicalInputs_equalFingerprints() {
            // TODO:
            // ErrorFingerprint f1 = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_KBO_001), "be.vdab");
            // ErrorFingerprint f2 = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_KBO_001), "be.vdab");
            // assertThat(f1).isEqualTo(f2);
        }

        @Test
        @DisplayName("same error for different KBO numbers produces equal fingerprints")
        void differentKboNumbers_equalFingerprints() {
            // Spec: strip_tenant_data removes KBO numbers so two occurrences of the
            // same error for different tenants produce the same fingerprint
            // TODO:
            // ErrorFingerprint f1 = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_KBO_001), "be.vdab");
            // ErrorFingerprint f2 = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_KBO_002), "be.vdab");
            // assertThat(f1).isEqualTo(f2);
        }

        @Test
        @DisplayName("same exception class in a different method is not equal")
        void sameExceptionDifferentMethod_notEqual() {
            // Spec: "if the same exception class occurs in a different method it is not a duplicate"
            // TODO:
            // ErrorFingerprint f1 = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_KBO_001), "be.vdab");
            // ErrorFingerprint f2 = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_DIFFERENT_METHOD), "be.vdab");
            // assertThat(f1).isNotEqualTo(f2);
        }

        @Test
        @DisplayName("different exception type produces different fingerprint")
        void differentExceptionType_notEqual() {
            // TODO:
            // ErrorFingerprint f1 = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_KBO_001), "be.vdab");
            // ErrorFingerprint f2 = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_DIFFERENT_EXCEPTION), "be.vdab");
            // assertThat(f1).isNotEqualTo(f2);
        }
    }

    @Nested
    @DisplayName("own-code frame extraction")
    class OwnCodeFrame {

        @Test
        @DisplayName("throwing_method is the first own-code frame in the stack trace")
        void throwingMethod_isFirstOwnCodeFrame() {
            // TODO:
            // ErrorFingerprint f = ErrorFingerprint.from(buildErrorLog(STACK_TRACE_KBO_001), "be.vdab");
            // assertThat(f.getThrowingMethod())
            //     .isEqualTo("be.vdab.app.service.UserService.processRequest");
        }

        @Test
        @DisplayName("third-party frames before own-code frames are skipped")
        void thirdPartyLeadingFrames_skipped() {
            // Stack trace where Spring frames appear before own-code frame
            // TODO:
            // String stackTraceWithSpringFirst =
            //     "java.lang.NullPointerException\n" +
            //     "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(...)\n" +
            //     "\tat be.vdab.app.service.UserService.processRequest(UserService.java:42)\n";
            // ErrorFingerprint f = ErrorFingerprint.from(buildErrorLog(stackTraceWithSpringFirst), "be.vdab");
            // assertThat(f.getThrowingMethod())
            //     .isEqualTo("be.vdab.app.service.UserService.processRequest");
        }
    }
}
