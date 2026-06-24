package be.vdab.logguard.infrastructure.config;

import be.vdab.logguard.domain.port.in.PollUseCase;
import be.vdab.logguard.domain.port.out.LlmPort;
import be.vdab.logguard.domain.port.out.OpenSearchPort;
import be.vdab.logguard.domain.port.out.PollCheckpointRepository;
import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import be.vdab.logguard.domain.service.PollService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Spring-free domain services from their ports, keeping {@code domain/} annotation-free.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public PollUseCase pollUseCase(OpenSearchPort openSearchPort,
                                   PollCheckpointRepository checkpointRepository,
                                   TerminalOutputPort terminalOutput,
                                   LlmPort llmPort) {
        return new PollService(openSearchPort, checkpointRepository, terminalOutput, llmPort);
    }
}
