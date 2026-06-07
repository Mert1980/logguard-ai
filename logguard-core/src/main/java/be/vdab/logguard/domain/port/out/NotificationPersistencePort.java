package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.Notification;

import java.util.List;

public interface NotificationPersistencePort {
    void save(Notification notification);
    List<Notification> findPersistedFailed();
}
