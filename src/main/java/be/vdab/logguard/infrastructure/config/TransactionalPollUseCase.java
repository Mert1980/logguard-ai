package be.vdab.logguard.infrastructure.config;

import be.vdab.logguard.domain.port.in.PollUseCase;
import be.vdab.logguard.domain.service.PollService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for one poll cycle (AR-9 / NFR-5). Keeps {@code domain/} Spring-free: the
 * {@code @Transactional} lives here, not on {@link PollService}. The transaction wraps the whole cycle —
 * suppression reload → fetch from OpenSearch → process all errors → advance checkpoint — so the
 * checkpoint advances only if the entire batch was delivered; any failure rolls back and leaves the
 * checkpoint untouched (re-fetched next cycle, no silent data loss).
 *
 * <p>Marked {@link Primary} so {@code PollScheduler} injects this wrapper rather than the raw
 * {@link PollService} bean. Caveat: the transaction is held across the OpenSearch query and the per-error
 * LLM calls — acceptable against local H2 here; revisit once dedup (Epic 4) bounds the per-cycle work.</p>
 */
@Component
@Primary
public class TransactionalPollUseCase implements PollUseCase {

    private final PollService delegate;

    public TransactionalPollUseCase(PollService delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional
    public void poll() {
        delegate.poll();
    }
}
