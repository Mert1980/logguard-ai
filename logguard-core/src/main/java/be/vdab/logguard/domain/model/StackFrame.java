package be.vdab.logguard.domain.model;

/** A parsed frame from a Java stack trace belonging to own-code packages. */
public record StackFrame(
        String className,
        String methodName,
        int lineNumber
) {}
