package be.vdab.logguard.producer.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Stand-in for a realistic VDAB own-code service. Its failure path throws from a
 * {@code be.vdab.*} frame so the resulting stack trace contains at least one own-code
 * frame (required for LogGuard fingerprinting in Epic 4 and LLM analysis in Epic 3).
 */
@Service
public class LabelService {

    private final Map<String, String> forwardingSources = Map.of(
            "WG-VDAB", "vdab-source",
            "WG-RVA", "rva-source"
    );

    /**
     * Resolves a forwarding source. For an unknown source the lookup returns {@code null}
     * and dereferencing it throws a {@link NullPointerException} originating in this class —
     * mirroring the "no template found for source ..." class of production errors.
     */
    public String forwardingSourceFor(String source) {
        String resolved = forwardingSources.get(source);
        return resolved.toUpperCase(); // NPE here for unknown sources -> be.vdab.* frame in the trace
    }
}
