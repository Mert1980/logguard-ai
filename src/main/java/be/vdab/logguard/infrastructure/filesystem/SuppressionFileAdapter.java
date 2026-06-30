package be.vdab.logguard.infrastructure.filesystem;

import be.vdab.logguard.domain.port.out.SuppressionFilePort;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import be.vdab.logguard.infrastructure.config.LogguardProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the developer-owned won't-fix list (FR-14). Re-read at the start of every poll cycle so a
 * hot-edited file takes effect within one cycle. {@link #loadHashes()} never throws and the adapter has
 * NO write/truncate/create path in any branch, including the exception handler (FR-16 / NFR-6 — the
 * read-only authority is also enforced structurally by {@link SuppressionFilePort} having no write method).
 *
 * <p>Failure handling (FR-15): an absent file is not an error — it means "nothing suppressed" (empty set).
 * A file that exists but cannot be read/parsed keeps the previously loaded set in memory and surfaces the
 * {@code ⚠️ Suppression file unreadable} warning via {@link TerminalOutputPort}. The reload is sequential
 * with the single-threaded poll cycle (FR-14), so the in-memory {@code lastKnown} needs no synchronisation.</p>
 */
@Component
public class SuppressionFileAdapter implements SuppressionFilePort {

    private final Path suppressionFile;
    private final TerminalOutputPort terminalOutput;
    private Set<String> lastKnown = Set.of();

    @Autowired
    public SuppressionFileAdapter(LogguardProperties properties, TerminalOutputPort terminalOutput) {
        this(properties.suppressionFilePath(), terminalOutput);
    }

    SuppressionFileAdapter(String suppressionFilePath, TerminalOutputPort terminalOutput) {
        this.suppressionFile = Path.of(suppressionFilePath);
        this.terminalOutput = terminalOutput;
    }

    @Override
    public Set<String> loadHashes() {
        // Absent file = nothing suppressed (FR-15) — not an error, no warning. Reset the last-known state.
        if (!Files.exists(suppressionFile)) {
            lastKnown = Set.of();
            return lastKnown;
        }
        try {
            List<String> lines = Files.readAllLines(suppressionFile, StandardCharsets.UTF_8);
            lastKnown = parse(lines);
            return lastKnown;
        } catch (IOException | RuntimeException e) {
            // Unreadable/parse error: keep the last known state, warn, keep polling. NEVER write here.
            terminalOutput.printSuppressionUnreadable();
            return lastKnown;
        }
    }

    /**
     * Each line: the text before the first {@code #} (or the whole line), trimmed; non-empty tokens are
     * hashes. This ignores blank/whitespace-only lines and full {@code #}-comment lines, strips the
     * {@code  # HumanLabel} comment from a hash line, and dedups (first occurrence wins) via the set.
     */
    private static Set<String> parse(List<String> lines) {
        Set<String> hashes = new LinkedHashSet<>();
        for (String line : lines) {
            int hashIdx = line.indexOf('#');
            String token = (hashIdx >= 0 ? line.substring(0, hashIdx) : line).strip();
            if (!token.isEmpty()) {
                hashes.add(token);
            }
        }
        return hashes;
    }
}
