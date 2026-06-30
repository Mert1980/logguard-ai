package be.vdab.logguard.infrastructure.terminal;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 4.4 formatting (FR-18 / FR-30 / FR-32): the per-error {@code Fingerprint:} line (bracketed hash,
 * present in BOTH the available and unavailable branches) and the {@code ⚑ Known / Won't Fix} label
 * (unbracketed hash). Captures {@code System.out} via a swapped {@link PrintStream}, restored in a finally.
 */
class TerminalOutputAdapterTest {

	private final TerminalOutputAdapter adapter = new TerminalOutputAdapter();

	private String capture(Runnable action) {
		PrintStream original = System.out;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
		try {
			action.run();
		} finally {
			System.setOut(original);
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}

	@Test
	void printAnalysis_available_endsWithBracketedFingerprintLine() {
		ErrorLog error = errorWith("java.lang.NullPointerException");
		LLMAnalysis analysis = LLMAnalysis.available("npe on null label", "Foo.bar:7", "guard the lookup");

		String out = capture(() -> adapter.printAnalysis(1, 1, error, analysis,
				"NullPointerException@Foo:7", "a1b2c3d4"));

		assertTrue(out.contains("  Fingerprint: NullPointerException@Foo:7  [a1b2c3d4]"),
				"Fingerprint line: two spaces before the bracketed hash; got:\n" + out);
	}

	@Test
	void printAnalysis_unavailable_stillEndsWithFingerprintLine() {
		ErrorLog error = errorWith("java.lang.NullPointerException");
		LLMAnalysis analysis = LLMAnalysis.unavailable("ollama unreachable");

		String out = capture(() -> adapter.printAnalysis(1, 1, error, analysis,
				"NullPointerException@Foo:7", "a1b2c3d4"));

		assertTrue(out.contains("analysis unavailable"), "failed analysis is still displayed (FR-24)");
		assertTrue(out.contains("  Fingerprint: NullPointerException@Foo:7  [a1b2c3d4]"),
				"FR-18: a failed analysis still carries the Fingerprint line; got:\n" + out);
	}

	@Test
	void printWontFixLabel_rendersFlagLabel_withoutBrackets() {
		String out = capture(() -> adapter.printWontFixLabel("NullPointerException@Foo:7", "a1b2c3d4"));

		assertTrue(out.contains("⚑ Known / Won't Fix: NullPointerException@Foo:7  a1b2c3d4"),
				"⚑ label: two spaces before the bare hash; got:\n" + out);
		assertFalse(out.contains("[a1b2c3d4]"), "the WontFix label must NOT bracket the hash (FR-32)");
	}

	@Test
	void printEscalation_cooling_printsKnownErrorWithReusedRootCause() {
		LLMAnalysis stored = LLMAnalysis.available("connection pool exhausted", "Foo.bar:7", "raise max-pool");
		Instant firstSeen = Instant.parse("2026-06-15T00:00:00Z");

		String out = capture(() -> adapter.printEscalation("NullPointerException@Foo:7", 100, firstSeen, stored, false));

		assertTrue(out.contains("⚠️ Known error NullPointerException@Foo:7 now seen 100× since " + firstSeen),
				"cooling escalation header; got:\n" + out);
		assertTrue(out.contains("   Root cause: connection pool exhausted"),
				"cooling escalation reuses the stored root cause; got:\n" + out);
	}

	@Test
	void printEscalation_cooling_nullStored_printsNoAnalysisOnFile() {
		Instant firstSeen = Instant.parse("2026-06-15T00:00:00Z");

		String out = capture(() -> adapter.printEscalation("NullPointerException@Foo:7", 10, firstSeen, null, false));

		assertTrue(out.contains("⚠️ Known error NullPointerException@Foo:7 now seen 10× since " + firstSeen),
				"got:\n" + out);
		assertTrue(out.contains("   Root cause: no analysis on file"),
				"null stored analysis renders the fallback; got:\n" + out);
	}

	@Test
	void printEscalation_wontFix_printsOneLineVolumeOverrideNoRootCause() {
		Instant firstSeen = Instant.parse("2026-06-15T00:00:00Z");

		String out = capture(() -> adapter.printEscalation("IllegalStateException@Bar:9", 1000, firstSeen, null, true));

		assertTrue(out.contains("⚠️ Won't-fix error IllegalStateException@Bar:9 now seen 1000× since "
						+ firstSeen + " — volume is unusually high"),
				"won't-fix volume override, one line; got:\n" + out);
		assertFalse(out.contains("Root cause:"), "the volume override carries no Root cause line; got:\n" + out);
	}

	private static ErrorLog errorWith(String exceptionType) {
		return new ErrorLog(exceptionType, "boom",
				"java.lang.NullPointerException\n\tat be.vdab.app.Foo.bar(Foo.java:7)",
				"svc", "app", "team", "local", Instant.parse("2026-06-15T00:00:00Z"), "cn=X");
	}
}
