package be.vdab.logguard.infrastructure.config;

import be.vdab.logguard.domain.model.PollCheckpoint;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.time.Duration;
import java.time.Instant;

/**
 * On first run (no {@link PollCheckpoint} persisted), asks the developer how far back to process and
 * seeds the checkpoint accordingly (FR-2). On subsequent runs the existing checkpoint is left untouched.
 *
 * <p>Runs as an {@link ApplicationRunner} so the prompt completes during startup, before any polling.
 * The lookback prompt is read from the interactive {@link Console}. (The architecture's AR-15 named
 * Spring Shell's {@code Terminal}, but Spring Shell 4.0.x is unsupported on Spring Boot 4.1 and does
 * not expose JLine on the compile classpath, so plain {@code System.console()} is used — the documented
 * fallback in the story.)</p>
 *
 * <p>When there is <em>no</em> interactive console (tests, CI, redirected I/O), the prompt is skipped
 * and the 24h default is applied — reading stdin there would block forever (e.g. under Surefire).</p>
 *
 * <p>The prompt is the only place {@code System.out} is used outside a terminal adapter; the
 * {@code TerminalOutputPort}/{@code TerminalOutputAdapter} (AR-10) arrive in Story 2.4. The pure
 * parsing logic is package-private so it can be unit-tested without a console.</p>
 */
@Component
public class FirstRunInitializer implements ApplicationRunner {

    static final int DEFAULT_LOOKBACK_HOURS = 24;
    static final String PROMPT = "First run detected. Process last [N] hours of history? (default: 24h)";

    private final PollCheckpointRepository repository;

    public FirstRunInitializer(PollCheckpointRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.load().isPresent()) {
            return; // subsequent run — use the existing checkpoint as-is
        }
        Instant lastSuccessfulPollAt = computeCheckpoint(promptForLookback(), Instant.now());
        repository.save(new PollCheckpoint(lastSuccessfulPollAt, null, 0));
    }

    /**
     * Reads one line from the interactive console. Returns {@code null} (⇒ default) when no console is
     * attached, so non-interactive launches (tests/CI) never block on stdin.
     */
    private String promptForLookback() {
        Console console = System.console();
        if (console == null) {
            System.out.println(PROMPT + " — no interactive console; defaulting to "
                    + DEFAULT_LOOKBACK_HOURS + "h");
            System.out.flush();
            return null;
        }
        console.printf("%s ", PROMPT);
        console.flush();
        return console.readLine();
    }

    /** now − parseHours(input) hours. {@code "0"} ⇒ now; blank/invalid ⇒ 24h default. */
    static Instant computeCheckpoint(String input, Instant now) {
        return now.minus(Duration.ofHours(parseHours(input)));
    }

    static int parseHours(String input) {
        if (input == null || input.isBlank()) {
            return DEFAULT_LOOKBACK_HOURS;
        }
        try {
            int hours = Integer.parseInt(input.trim());
            return hours < 0 ? DEFAULT_LOOKBACK_HOURS : hours;
        } catch (NumberFormatException e) {
            return DEFAULT_LOOKBACK_HOURS;
        }
    }
}
