package be.vdab.logguard.infrastructure.filesystem;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-14/15/16: parse the won't-fix list (hash before the {@code #} comment), ignore blanks/comments, dedup,
 * treat an absent file as empty, keep the last known state on a read error (with a warning), and never write.
 * Pure unit test — {@code @TempDir} + a recording terminal fake, no Spring context.
 */
class SuppressionFileAdapterTest {

	private final RecordingTerminal terminal = new RecordingTerminal();

	@Test
	void parsesHashesStrippingCommentsIgnoringBlanksAndCommentsAndDeduping(@TempDir Path tmp) throws IOException {
		Path file = tmp.resolve("suppression.txt");
		Files.writeString(file, """
				# a full-line comment is ignored
				a3f9c2b1  # NPE@LabelV2Config:21
				   b1c2d3e4   # leading spaces, IllegalState@Foo:7

				   # indented comment ignored
				deadbeef
				a3f9c2b1  # duplicate hash — collapses
				""");

		SuppressionFileAdapter adapter = new SuppressionFileAdapter(file.toString(), terminal);
		Set<String> hashes = adapter.loadHashes();

		assertEquals(Set.of("a3f9c2b1", "b1c2d3e4", "deadbeef"), hashes);
		assertEquals(0, terminal.unreadableCalls);
	}

	@Test
	void absentFileReturnsEmptySetWithoutWarning(@TempDir Path tmp) {
		Path file = tmp.resolve("does-not-exist.txt");

		SuppressionFileAdapter adapter = new SuppressionFileAdapter(file.toString(), terminal);

		assertTrue(adapter.loadHashes().isEmpty());
		assertEquals(0, terminal.unreadableCalls, "an absent file is not an error condition");
	}

	@Test
	void unreadableFileKeepsLastKnownStateAndWarns(@TempDir Path tmp) throws IOException {
		Path file = tmp.resolve("suppression.txt");
		Files.writeString(file, "a3f9c2b1  # keep me\n");
		SuppressionFileAdapter adapter = new SuppressionFileAdapter(file.toString(), terminal);

		Set<String> firstLoad = adapter.loadHashes();
		assertEquals(Set.of("a3f9c2b1"), firstLoad);

		// Make the path unreadable without touching permissions: replace the file with a directory at the
		// same path, so readAllLines throws IOException on every platform.
		Files.delete(file);
		Files.createDirectory(file);

		Set<String> afterBreak = adapter.loadHashes();
		assertEquals(Set.of("a3f9c2b1"), afterBreak, "last known state is kept when the file becomes unreadable");
		assertEquals(1, terminal.unreadableCalls, "the FR-15 warning is surfaced");
	}

	@Test
	void doesNotModifyTheFile(@TempDir Path tmp) throws IOException {
		Path file = tmp.resolve("suppression.txt");
		String content = "a3f9c2b1  # NPE\nb1c2d3e4  # ISE\n";
		Files.writeString(file, content);
		byte[] before = Files.readAllBytes(file);

		new SuppressionFileAdapter(file.toString(), terminal).loadHashes();

		assertArrayEquals(before, Files.readAllBytes(file), "the adapter must never write to the suppression file");
		assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
	}

	/** Counts the suppression-unreadable warning; other methods are irrelevant here. */
	private static final class RecordingTerminal implements TerminalOutputPort {
		int unreadableCalls;

		@Override
		public void printProgress(String serviceName, int count) {
		}

		@Override
		public void printAnalysis(int index, int total, ErrorLog error, LLMAnalysis analysis,
								  String humanLabel, String hash) {
		}

		@Override
		public void printWontFixLabel(String humanLabel, String hash) {
		}

		@Override
		public void printDegraded(Instant degradationStartedAt, int consecutiveFailures) {
		}

		@Override
		public void printRecovery(Instant resumedAt) {
		}

		@Override
		public void printSuppressionUnreadable() {
			unreadableCalls++;
		}
	}
}
