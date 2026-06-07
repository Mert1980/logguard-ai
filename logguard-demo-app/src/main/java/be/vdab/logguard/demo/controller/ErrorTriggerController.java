package be.vdab.logguard.demo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * REST endpoints that deliberately throw real exceptions,
 * causing the OpenSearchLogAppender to ship them to OpenSearch.
 *
 * Usage (all return 500 and log the exception):
 *   GET  /demo/npe                           — NullPointerException
 *   GET  /demo/number-format                 — NumberFormatException
 *   GET  /demo/illegal-state                 — IllegalStateException
 *   GET  /demo/stack-overflow                — StackOverflowError
 *   GET  /demo/db-error                      — simulated database failure
 *   GET  /demo/business-error?kbo=0123456789 — simulated business rule violation with KBO (tests deduplication)
 *   GET  /demo/status                        — health check
 */
@RestController
@RequestMapping("/demo")
public class ErrorTriggerController {

    private static final Logger log = LoggerFactory.getLogger(ErrorTriggerController.class);

    @GetMapping("/npe")
    public String triggerNullPointerException() {
        try {
            String s = null;
            return s.toLowerCase(); // throws NPE
        } catch (NullPointerException e) {
            log.error("Null pointer in user profile service while processing request", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Triggered", e);
        }
    }

    @GetMapping("/number-format")
    public String triggerNumberFormatException() {
        try {
            int result = Integer.parseInt("not-a-number");
            return String.valueOf(result);
        } catch (NumberFormatException e) {
            log.error("Invalid number format received from payment gateway response", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Triggered", e);
        }
    }

    @GetMapping("/illegal-state")
    public String triggerIllegalStateException() {
        try {
            throw new IllegalStateException("Order cannot be cancelled: already shipped to KBO=0123456789");
        } catch (IllegalStateException e) {
            log.error("Illegal state in order management service", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Triggered", e);
        }
    }

    @GetMapping("/stack-overflow")
    public String triggerStackOverflow() {
        try {
            infiniteRecurse(0);
            return "ok";
        } catch (StackOverflowError e) {
            log.error("Stack overflow in recursive tree traversal", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Triggered", e.getMessage());
        }
    }

    @GetMapping("/db-error")
    public String triggerDatabaseError() {
        try {
            throw new RuntimeException("Connection pool exhausted: Unable to acquire connection within 30000ms for KBO=9876543210");
        } catch (RuntimeException e) {
            log.error("Database connection failure in repository layer", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Triggered", e);
        }
    }

    /**
     * Triggers the same exception with a different KBO number.
     * Used to demonstrate deduplication: both should produce the same fingerprint.
     */
    @GetMapping("/business-error")
    public String triggerBusinessError(@RequestParam(defaultValue = "0123456789") String kbo) {
        try {
            throw new IllegalArgumentException(
                    "Validation failed for enterprise KBO=" + kbo + ": VAT number does not match registry");
        } catch (IllegalArgumentException e) {
            log.error("Business rule violation in VAT validation service", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Triggered", e);
        }
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of(
                "status", "UP",
                "endpoints", "/demo/npe, /demo/number-format, /demo/illegal-state, /demo/stack-overflow, /demo/db-error, /demo/business-error?kbo=XXX"
        );
    }

    private void infiniteRecurse(int depth) {
        infiniteRecurse(depth + 1);
    }
}
