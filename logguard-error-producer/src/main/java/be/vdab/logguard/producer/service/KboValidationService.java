package be.vdab.logguard.producer.service;

import org.springframework.stereotype.Service;

/**
 * Stand-in VDAB own-code service whose failure path throws an {@link IllegalArgumentException}
 * from a {@code be.vdab.*} frame. The message embeds the offending KBO number, which also lets the
 * demo show LogGuard's tenant-data redaction (FR-21) stripping a Belgian KBO before the LLM call.
 */
@Service
public class KboValidationService {

    /**
     * Simulates strict KBO (Belgian company number) format validation; a malformed value
     * is rejected with an {@link IllegalArgumentException}.
     */
    public void validate(String kbo) {
        throw new IllegalArgumentException(
                "Invalid KBO number '" + kbo + "': expected format 0XXX.XXX.XXX");
    }
}
