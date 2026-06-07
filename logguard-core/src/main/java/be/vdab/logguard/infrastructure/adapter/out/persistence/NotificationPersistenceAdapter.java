package be.vdab.logguard.infrastructure.adapter.out.persistence;

import be.vdab.logguard.domain.model.*;
import be.vdab.logguard.domain.port.out.NotificationPersistencePort;
import be.vdab.logguard.infrastructure.adapter.out.persistence.jpa.NotificationJpaEntity;
import be.vdab.logguard.infrastructure.adapter.out.persistence.jpa.NotificationJpaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class NotificationPersistenceAdapter implements NotificationPersistencePort {

    private static final Logger log = LoggerFactory.getLogger(NotificationPersistenceAdapter.class);

    private final NotificationJpaRepository repository;
    private final ObjectMapper objectMapper;

    public NotificationPersistenceAdapter(NotificationJpaRepository repository, ObjectMapper objectMapper) {
        this.repository   = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void save(Notification notification) {
        NotificationJpaEntity entity = (notification.getId() != null)
                ? repository.findById(notification.getId()).orElse(new NotificationJpaEntity())
                : new NotificationJpaEntity();

        mapToEntity(notification, entity);
        NotificationJpaEntity saved = repository.save(entity);
        notification.setId(saved.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Notification> findPersistedFailed() {
        return repository.findByStatusAndPersistedAtIsNotNull(NotificationStatus.FAILED.name())
                .stream()
                .map(this::mapToDomain)
                .toList();
    }

    private void mapToEntity(Notification n, NotificationJpaEntity e) {
        ErrorLog el = n.getErrorLog();
        e.setErrorExceptionType(el.exceptionType());
        e.setErrorMessage(el.errorMessage());
        e.setErrorStackTrace(el.stackTrace());
        e.setErrorServiceName(el.serviceName());
        e.setErrorAppName(el.appName());
        e.setErrorTeam(el.team());
        e.setErrorEnvironment(el.environment());
        e.setErrorSeverity(el.severity());
        e.setErrorOccurredAt(el.occurredAt());

        switch (n.getAnalysis()) {
            case LLMAnalysis.Available a -> {
                e.setLlmAvailable(true);
                e.setLlmSummary(a.summary());
                e.setLlmRootCause(a.rootCause());
                e.setLlmSuggestedFix(a.suggestedFix());
            }
            case LLMAnalysis.Unavailable u -> {
                e.setLlmAvailable(false);
                e.setLlmUnavailabilityReason(u.reason());
            }
        }

        e.setGitlabLinksJson(toJson(n.getGitLabLinks()));
        e.setStatus(n.getStatus().name());
        e.setAttemptCount(n.getAttemptCount());
        e.setFailureReason(n.getFailureReason());
        e.setPersistedAt(n.getPersistedAt());
    }

    private Notification mapToDomain(NotificationJpaEntity e) {
        ErrorLog errorLog = new ErrorLog(
                e.getErrorExceptionType(), e.getErrorMessage(), e.getErrorStackTrace(),
                e.getErrorServiceName(), e.getErrorAppName(), e.getErrorTeam(),
                e.getErrorEnvironment(), e.getErrorSeverity(), e.getErrorOccurredAt());

        LLMAnalysis analysis = e.isLlmAvailable()
                ? new LLMAnalysis.Available(e.getLlmSummary(), e.getLlmRootCause(), e.getLlmSuggestedFix())
                : new LLMAnalysis.Unavailable(e.getLlmUnavailabilityReason());

        List<GitLabLink> links = fromJson(e.getGitlabLinksJson());

        return new Notification(
                e.getId(), errorLog, analysis, links,
                NotificationStatus.valueOf(e.getStatus()),
                e.getAttemptCount(), e.getFailureReason(), e.getPersistedAt());
    }

    private String toJson(List<GitLabLink> links) {
        try {
            return objectMapper.writeValueAsString(links);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize GitLabLinks", ex);
            return "[]";
        }
    }

    private List<GitLabLink> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize GitLabLinks", ex);
            return List.of();
        }
    }
}
