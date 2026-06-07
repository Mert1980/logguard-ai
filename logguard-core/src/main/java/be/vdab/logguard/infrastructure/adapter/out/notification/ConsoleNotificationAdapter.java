package be.vdab.logguard.infrastructure.adapter.out.notification;

import be.vdab.logguard.domain.model.DeliveryResult;
import be.vdab.logguard.domain.model.Notification;
import be.vdab.logguard.domain.port.out.NotificationDeliveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local-profile replacement for Google Chat.
 * Prints the notification to stdout with visual framing so it's easy to spot in the terminal.
 * Demonstrates hexagonal architecture: swapping infrastructure without touching domain code.
 */
@Component
@Profile("local")
public class ConsoleNotificationAdapter implements NotificationDeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(ConsoleNotificationAdapter.class);

    // ANSI colours — ignored on terminals that don't support them
    private static final String RESET  = "[0m";
    private static final String RED    = "[31m";
    private static final String YELLOW = "[33m";
    private static final String CYAN   = "[36m";
    private static final String BOLD   = "[1m";

    private final NotificationMessageFormatter formatter;

    public ConsoleNotificationAdapter(NotificationMessageFormatter formatter) {
        this.formatter = formatter;
    }

    @Override
    public DeliveryResult deliver(Notification notification) {
        String border = RED + BOLD + "═".repeat(80) + RESET;
        System.out.println(border);
        System.out.println(CYAN + BOLD + "  LOGGUARD NOTIFICATION  [local mode — would go to Google Chat]" + RESET);
        System.out.println(border);
        System.out.println(formatter.formatText(notification));
        System.out.println(border);
        System.out.println();
        return new DeliveryResult.Succeeded();
    }

    @Override
    public void deliverDegradationAlert(int consecutiveFailures) {
        String border = YELLOW + BOLD + "▶".repeat(80) + RESET;
        System.out.println(border);
        System.out.println(YELLOW + BOLD + "  LOGGUARD DEGRADATION ALERT  [local mode]" + RESET);
        System.out.println(formatter.formatDegradationAlert(consecutiveFailures));
        System.out.println(border);
        System.out.println();
    }
}
