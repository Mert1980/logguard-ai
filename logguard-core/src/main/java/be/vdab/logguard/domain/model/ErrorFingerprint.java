package be.vdab.logguard.domain.model;

public record ErrorFingerprint(
        String exceptionType,
        String throwingMethod,
        String stackTraceSequence
) {}
