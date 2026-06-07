package be.vdab.logguard.domain.model;

public record GitLabLink(
        String className,
        int lineNumber,
        String url
) {}
