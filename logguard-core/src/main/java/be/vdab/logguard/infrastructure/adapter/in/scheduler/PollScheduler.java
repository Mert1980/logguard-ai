package be.vdab.logguard.infrastructure.adapter.in.scheduler;

import be.vdab.logguard.domain.port.in.PollForErrorsUseCase;
import be.vdab.logguard.domain.port.in.RetryFailedNotificationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter — triggers the poll cycle on the configured interval.
 * Both use cases run together on each tick, matching the spec's shared trigger:
 *   PollCheckpoint.last_successful_poll_at + poll_interval <= now
 */
@Component
public class PollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);

    private final PollForErrorsUseCase pollForErrors;
    private final RetryFailedNotificationsUseCase retryFailed;

    public PollScheduler(PollForErrorsUseCase pollForErrors,
                         RetryFailedNotificationsUseCase retryFailed) {
        this.pollForErrors = pollForErrors;
        this.retryFailed   = retryFailed;
    }

    @Scheduled(fixedDelayString = "${logguard.poll-interval}", initialDelayString = "PT5S")
    public void poll() {
        log.debug("Poll cycle starting");
        retryFailed.execute();    // retry persisted failures first
        pollForErrors.execute();  // then poll for new errors
        log.debug("Poll cycle complete");
    }
}
