package be.vdab.logguard.infrastructure.config;

import be.vdab.logguard.domain.port.in.PollUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires a poll cycle every {@code logguard.poll-interval} (default 5m), starting shortly after startup.
 * Disabled in tests via {@code logguard.scheduler.enabled=false} so {@code mvn test} does no network I/O.
 *
 * <p>Failures are logged (WARN) and the loop continues — full degradation detection/banner is Story 2.6.
 * The first-run prompt completes via an {@code ApplicationRunner} after scheduling starts, so the first
 * cycle may find no checkpoint yet; {@code PollService} simply skips that cycle.</p>
 */
@Component
@ConditionalOnProperty(name = "logguard.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);

    private final PollUseCase pollUseCase;

    public PollScheduler(PollUseCase pollUseCase) {
        this.pollUseCase = pollUseCase;
    }

    @Scheduled(fixedDelayString = "${logguard.poll-interval:5m}", initialDelayString = "2s")
    public void poll() {
        try {
            pollUseCase.poll();
        } catch (Exception e) {
            log.warn("Poll cycle failed: {}", e.getMessage());
        }
    }
}
