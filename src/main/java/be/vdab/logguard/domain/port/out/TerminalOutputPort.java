package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.ErrorLog;

import java.util.List;

/**
 * Outbound port for user-facing terminal output. The only implementation is allowed to touch
 * {@code System.out} (AR-10). No Spring annotations here.
 *
 * <p>Minimal Epic-2 surface: print one service's error block. (Escalation/won't-fix/degradation
 * output methods arrive with their stories.)</p>
 */
public interface TerminalOutputPort {

    /** Print the buffered errors for one service as a block (FR-27/28/30 shape). */
    void printServiceErrors(String serviceName, List<ErrorLog> errors);
}
