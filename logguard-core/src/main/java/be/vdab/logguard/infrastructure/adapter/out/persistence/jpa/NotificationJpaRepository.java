package be.vdab.logguard.infrastructure.adapter.out.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationJpaRepository extends JpaRepository<NotificationJpaEntity, Long> {

    /** Spec rule RetryPersistedNotifications: find FAILED notifications that have been persisted. */
    List<NotificationJpaEntity> findByStatusAndPersistedAtIsNotNull(String status);
}
