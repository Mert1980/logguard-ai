package be.vdab.logguard.domain.service;

import be.vdab.logguard.domain.model.DeliveryResult;
import be.vdab.logguard.domain.model.Notification;
import be.vdab.logguard.domain.port.in.RetryFailedNotificationsUseCase;
import be.vdab.logguard.domain.port.out.NotificationDeliveryPort;
import be.vdab.logguard.domain.port.out.NotificationPersistencePort;
import be.vdab.logguard.infrastructure.config.LogGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationService implements RetryFailedNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationDeliveryPort deliveryPort;
    private final NotificationPersistencePort persistencePort;
    private final LogGuardProperties properties;

    public NotificationService(NotificationDeliveryPort deliveryPort,
                               NotificationPersistencePort persistencePort,
                               LogGuardProperties properties) {
        this.deliveryPort  = deliveryPort;
        this.persistencePort = persistencePort;
        this.properties    = properties;
    }

    /** Attempts delivery and updates the notification status. Called after creation. */
    public void deliver(Notification notification) {
        DeliveryResult result = deliveryPort.deliver(notification);
        switch (result) {
            case DeliveryResult.Succeeded() -> {
                notification.markDelivered();
                log.info("Notification delivered for error: {}", notification.getErrorLog().exceptionType());
            }
            case DeliveryResult.Failed(String reason) -> {
                notification.recordFailure(reason, properties.maxDeliveryAttempts());
                log.warn("Delivery attempt {} failed for {}: {}",
                        notification.getAttemptCount(),
                        notification.getErrorLog().exceptionType(),
                        reason);
            }
        }
        persistencePort.save(notification);
    }

    /** Spec rule RetryPersistedNotifications: resets FAILED+persisted notifications to PENDING. */
    @Override
    public void execute() {
        List<Notification> persisted = persistencePort.findPersistedFailed();
        if (persisted.isEmpty()) return;

        log.info("Retrying {} persisted failed notifications", persisted.size());
        for (Notification notification : persisted) {
            notification.resetForRetry();
            persistencePort.save(notification);
            deliver(notification);
        }
    }
}
