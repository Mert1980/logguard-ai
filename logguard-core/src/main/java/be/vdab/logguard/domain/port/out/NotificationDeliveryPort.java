package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.DeliveryResult;
import be.vdab.logguard.domain.model.Notification;

public interface NotificationDeliveryPort {
    DeliveryResult deliver(Notification notification);
    void deliverDegradationAlert(int consecutiveFailures);
}
