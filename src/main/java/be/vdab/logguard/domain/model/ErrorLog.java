package be.vdab.logguard.domain.model;

import java.time.Instant;

/**
 * One ERROR-level log entry read from OpenSearch. Pure domain value object — no Spring/JPA annotations.
 * Field-to-source mapping (companion document {@code _source.*}) is handled by the OpenSearch adapter.
 *
 * @param exceptionType     {@code structured.error.type}
 * @param errorMessage      {@code structured.error.message}
 * @param stackTrace        {@code structured.error.stack_trace}
 * @param serviceName       {@code structured.service.name}
 * @param appName           {@code kubernetes.labels.appName}
 * @param team              {@code kubernetes.namespace_labels.vdab_be_team}
 * @param environment       {@code kubernetes.namespace_labels.vdab_be_environment}
 * @param occurredAt        {@code @timestamp}
 * @param vdabAuthorization {@code vdab.authorization} (LDAP DN; on-prem only — never to a cloud LLM, FR-20)
 */
public record ErrorLog(
        String exceptionType,
        String errorMessage,
        String stackTrace,
        String serviceName,
        String appName,
        String team,
        String environment,
        Instant occurredAt,
        String vdabAuthorization
) {
}
