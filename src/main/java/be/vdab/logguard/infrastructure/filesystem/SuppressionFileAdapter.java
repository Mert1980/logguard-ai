package be.vdab.logguard.infrastructure.filesystem;

import be.vdab.logguard.domain.port.out.SuppressionFilePort;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Story 2.5 stub for {@link SuppressionFilePort}: returns an empty set (nothing suppressed) so the poll
 * loop can establish the FR-14 ordering contract (suppression reload happens before any error in the
 * batch is processed). The real file parsing + hot-reload + "⚠️ unreadable → last known state" behaviour
 * arrives in Story 4.3 — this adapter never gains a write path (FR-16 / NFR-6).
 */
@Component
public class SuppressionFileAdapter implements SuppressionFilePort {

    @Override
    public Set<String> loadHashes() {
        return Set.of();
    }
}
