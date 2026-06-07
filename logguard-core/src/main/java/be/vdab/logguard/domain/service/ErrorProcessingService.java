package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.*;
import be.vdab.logguard.domain.port.in.PollForErrorsUseCase;
import be.vdab.logguard.domain.port.out.*;
import be.vdab.logguard.infrastructure.config.LogGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Implements the spec rules: PollForErrors, HandleOpenSearchUnreachable, ProcessNewError, AnalyseError.
 * Orchestrates the full poll cycle without touching infrastructure directly.
 */
@Service
public class ErrorProcessingService implements PollForErrorsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ErrorProcessingService.class);

    private final ErrorLogSearchPort errorLogSearchPort;
    private final DeduplicationPort deduplicationPort;
    private final LLMAnalysisPort llmAnalysisPort;
    private final GitLabLinkResolverPort gitLabLinkResolverPort;
    private final NotificationPersistencePort notificationPersistencePort;
    private final NotificationDeliveryPort notificationDeliveryPort;
    private final CheckpointPersistencePort checkpointPersistencePort;
    private final FingerprintComputer fingerprintComputer;
    private final NotificationService notificationService;
    private final LogGuardProperties properties;

    public ErrorProcessingService(ErrorLogSearchPort errorLogSearchPort,
                                  DeduplicationPort deduplicationPort,
                                  LLMAnalysisPort llmAnalysisPort,
                                  GitLabLinkResolverPort gitLabLinkResolverPort,
                                  NotificationPersistencePort notificationPersistencePort,
                                  NotificationDeliveryPort notificationDeliveryPort,
                                  CheckpointPersistencePort checkpointPersistencePort,
                                  FingerprintComputer fingerprintComputer,
                                  NotificationService notificationService,
                                  LogGuardProperties properties) {
        this.errorLogSearchPort      = errorLogSearchPort;
        this.deduplicationPort       = deduplicationPort;
        this.llmAnalysisPort         = llmAnalysisPort;
        this.gitLabLinkResolverPort  = gitLabLinkResolverPort;
        this.notificationPersistencePort = notificationPersistencePort;
        this.notificationDeliveryPort    = notificationDeliveryPort;
        this.checkpointPersistencePort   = checkpointPersistencePort;
        this.fingerprintComputer     = fingerprintComputer;
        this.notificationService     = notificationService;
        this.properties              = properties;
    }

    @Override
    public void execute() {
        PollCheckpoint checkpoint = checkpointPersistencePort.loadOrCreate();

        if (!errorLogSearchPort.isReachable()) {
            handleUnreachable(checkpoint);
            return;
        }

        // Spec rule PollForErrors: collect new errors, process each, then advance cursor
        List<ErrorLog> newErrors = errorLogSearchPort.findSince(checkpoint.getLastSuccessfulPollAt());
        log.info("Poll found {} new error(s) since {}", newErrors.size(), checkpoint.getLastSuccessfulPollAt());

        for (ErrorLog errorLog : newErrors) {
            try {
                processError(errorLog);
            } catch (Exception e) {
                // One failing error must not block the rest or the cursor advance
                log.error("Failed to process error log [{}]: {}", errorLog.exceptionType(), e.getMessage(), e);
            }
        }

        // Advance cursor only after all errors have been handed off (spec guidance)
        checkpoint.recordSuccess();
        checkpointPersistencePort.save(checkpoint);
    }

    private void processError(ErrorLog errorLog) {
        ErrorFingerprint fingerprint = fingerprintComputer.compute(
                errorLog, properties.ownCodePackagePrefixes());

        // Spec rule ProcessNewError: silently discard duplicates
        if (deduplicationPort.isDuplicate(fingerprint)) {
            log.debug("Skipping duplicate: {}", fingerprint.throwingMethod());
            return;
        }
        deduplicationPort.record(fingerprint, properties.deduplicationWindow());

        // Spec rule AnalyseError
        LLMAnalysis analysis = llmAnalysisPort.analyse(errorLog);
        List<GitLabLink> links = gitLabLinkResolverPort.resolve(errorLog, properties.ownCodePackagePrefixes());

        Notification notification = new Notification(errorLog, analysis, links);
        notificationPersistencePort.save(notification);

        // Spec rule DeliverNotification: fire when status becomes pending
        notificationService.deliver(notification);
    }

    private void handleUnreachable(PollCheckpoint checkpoint) {
        log.warn("OpenSearch unreachable — cursor NOT advanced (catch-up on next poll)");
        checkpoint.recordFailure();
        boolean degraded = checkpoint.hasReachedDegradationThreshold(
                properties.maxConsecutivePollFailures());
        checkpointPersistencePort.save(checkpoint);

        // Spec rule HandleOpenSearchUnreachable: emit OpenSearchDegradationDetected at threshold
        if (degraded) {
            log.error("OpenSearch degradation threshold reached ({} consecutive failures)",
                    checkpoint.getConsecutivePollFailures());
            notificationDeliveryPort.deliverDegradationAlert(checkpoint.getConsecutivePollFailures());
        }
    }
}
