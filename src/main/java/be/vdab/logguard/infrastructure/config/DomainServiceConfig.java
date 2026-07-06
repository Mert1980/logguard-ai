package be.vdab.logguard.infrastructure.config;

import be.vdab.logguard.domain.port.out.DeduplicationRecordRepository;
import be.vdab.logguard.domain.port.out.LlmPort;
import be.vdab.logguard.domain.port.out.OpenSearchPort;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import be.vdab.logguard.domain.port.out.SuppressionFilePort;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import be.vdab.logguard.domain.service.FingerprintService;
import be.vdab.logguard.domain.service.PollService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Spring-free domain services from their ports, keeping {@code domain/} annotation-free. The
 * concrete {@link PollService} bean is exposed here; the {@code PollUseCase} that callers inject is the
 * {@code @Transactional} {@code TransactionalPollUseCase} wrapper (marked {@code @Primary}).
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public FingerprintService fingerprintService(LogguardProperties properties) {
        return new FingerprintService(properties.ownCodePackagePrefixes());
    }

    @Bean
    public PollService pollService(OpenSearchPort openSearchPort,
                                   PollCheckpointRepository checkpointRepository,
                                   TerminalOutputPort terminalOutput,
                                   LlmPort llmPort,
                                   SuppressionFilePort suppressionFilePort,
                                   FingerprintService fingerprintService,
                                   DeduplicationRecordRepository dedupRepository,
                                   LogguardProperties properties) {
        return new PollService(openSearchPort, checkpointRepository, terminalOutput, llmPort,
                suppressionFilePort, fingerprintService, dedupRepository,
                properties.deduplicationWindow(), properties.opensearch().refreshWindow(),
                properties.maxConsecutivePollFailures(), properties.escalationThresholds());
    }
}
