package be.vdab.logguard.domain.model;

import java.time.Instant;

public record ErrorLog(
        String exceptionType,
        String errorMessage,
        String stackTrace,
        String serviceName,
        String appName,
        String team,
        String environment,
        String severity,
        Instant occurredAt
) {}
