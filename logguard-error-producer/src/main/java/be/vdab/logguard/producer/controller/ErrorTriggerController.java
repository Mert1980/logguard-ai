package be.vdab.logguard.producer.controller;

import be.vdab.logguard.producer.service.LabelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers a realistic {@code be.vdab.*} error on demand. The error is logged at ERROR level,
 * which routes it through {@code OpenSearchErrorAppender} into the local OpenSearch error index
 * with the nested field layout LogGuard reads.
 */
@RestController
public class ErrorTriggerController {

    private static final Logger log = LoggerFactory.getLogger(ErrorTriggerController.class);

    private final LabelService labelService;

    public ErrorTriggerController(LabelService labelService) {
        this.labelService = labelService;
    }

    /**
     * POST /trigger-error — throws and logs a be.vdab.* exception.
     *
     * @param service simulated service name (lands at structured.service.name); lets a tester
     *                produce errors for several services to exercise grouped output later.
     * @param source  unknown forwarding source that provokes the failure (default WG-CRM).
     */
    @PostMapping("/trigger-error")
    public ResponseEntity<String> triggerError(
            @RequestParam(defaultValue = "orgbeheer-service") String service,
            @RequestParam(defaultValue = "WG-CRM") String source) {
        MDC.put("serviceName", service);
        try {
            labelService.forwardingSourceFor(source);
            return ResponseEntity.ok("no error produced for source '" + source + "'");
        } catch (RuntimeException ex) {
            log.error("Failed to resolve forwarding source '{}' in service '{}'", source, service, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("error produced and shipped to OpenSearch: " + ex.getClass().getName());
        } finally {
            MDC.remove("serviceName");
        }
    }
}
