package be.vdab.logguard.producer.controller;

import be.vdab.logguard.producer.service.KboValidationService;
import be.vdab.logguard.producer.service.LabelService;
import be.vdab.logguard.producer.service.MatchingQuotaService;
import be.vdab.logguard.producer.service.VacatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Triggers realistic {@code be.vdab.*} errors on demand. Each error is logged at ERROR level, which
 * routes it through {@code OpenSearchErrorAppender} into the local OpenSearch error index with the
 * nested field layout LogGuard reads.
 *
 * <p>The {@code type} parameter selects among several distinct exception types, each thrown from a
 * different {@code be.vdab.*} service method — so each produces a <strong>different LogGuard
 * fingerprint</strong>. The {@code count} parameter ships the same error N times in one call, which
 * drives the deduplication, escalation-threshold and won't-fix demos.</p>
 */
@RestController
public class ErrorTriggerController {

    private static final Logger log = LoggerFactory.getLogger(ErrorTriggerController.class);

    /** Error scenarios selectable via {@code ?type=}; each maps to a distinct exception + fingerprint. */
    private static final List<String> TYPES = List.of("npe", "state", "validation", "quota");

    private final LabelService labelService;
    private final VacatureService vacatureService;
    private final KboValidationService kboValidationService;
    private final MatchingQuotaService matchingQuotaService;

    public ErrorTriggerController(LabelService labelService,
                                  VacatureService vacatureService,
                                  KboValidationService kboValidationService,
                                  MatchingQuotaService matchingQuotaService) {
        this.labelService = labelService;
        this.vacatureService = vacatureService;
        this.kboValidationService = kboValidationService;
        this.matchingQuotaService = matchingQuotaService;
    }

    /**
     * POST /trigger-error — throws and logs {@code count} copies of a {@code be.vdab.*} exception.
     *
     * @param type    error scenario: {@code npe} (default, NullPointerException — LabelService),
     *                {@code state} (IllegalStateException — VacatureService),
     *                {@code validation} (IllegalArgumentException — KboValidationService),
     *                {@code quota} (ArithmeticException — MatchingQuotaService). Distinct fingerprints.
     * @param service simulated service name (lands at structured.service.name) — lets a tester produce
     *                errors for several services to exercise grouped, most-affected-first output.
     * @param source  free-text input passed to the scenario (default WG-CRM): the unknown forwarding
     *                source (npe), vacancy id (state), or KBO number (validation). Ignored by quota.
     * @param count   how many copies to ship in this call (default 1) — drives dedup/escalation demos.
     */
    @PostMapping("/trigger-error")
    public ResponseEntity<String> triggerError(
            @RequestParam(defaultValue = "npe") String type,
            @RequestParam(defaultValue = "orgbeheer-service") String service,
            @RequestParam(defaultValue = "WG-CRM") String source,
            @RequestParam(defaultValue = "1") int count) {

        String scenario = type.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(scenario)) {
            return ResponseEntity.badRequest()
                    .body("Unknown type '" + type + "'. Use one of: " + TYPES);
        }
        if (count < 1) {
            return ResponseEntity.badRequest().body("count must be >= 1");
        }

        MDC.put("serviceName", service);
        try {
            int shipped = 0;
            String exceptionClass = null;
            for (int i = 0; i < count; i++) {
                try {
                    raise(scenario, source);   // throws for every scenario except npe with a known source
                } catch (RuntimeException ex) {
                    log.error("Triggered '{}' error in service '{}' (input '{}')", scenario, service, source, ex);
                    exceptionClass = ex.getClass().getName();
                    shipped++;
                }
            }
            if (shipped == 0) {
                return ResponseEntity.ok("no error produced for type '" + scenario + "' / input '" + source + "'");
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("shipped " + shipped + "× " + exceptionClass + " to OpenSearch (service '" + service + "')");
        } finally {
            MDC.remove("serviceName");
        }
    }

    /** Invoke the selected scenario's {@code be.vdab.*} service so the throw originates in own code. */
    private void raise(String scenario, String source) {
        switch (scenario) {
            case "npe" -> labelService.forwardingSourceFor(source);
            case "state" -> vacatureService.publish(source);
            case "validation" -> kboValidationService.validate(source);
            case "quota" -> matchingQuotaService.remainingQuota(100, 0);
            default -> throw new IllegalStateException("unreachable — type validated above");
        }
    }
}
