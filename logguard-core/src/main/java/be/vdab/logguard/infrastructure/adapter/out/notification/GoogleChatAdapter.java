package be.vdab.logguard.infrastructure.adapter.out.notification;

import be.vdab.logguard.domain.model.DeliveryResult;
import be.vdab.logguard.domain.model.Notification;
import be.vdab.logguard.domain.port.out.NotificationDeliveryPort;
import be.vdab.logguard.infrastructure.config.LogGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@Profile("!local")
public class GoogleChatAdapter implements NotificationDeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatAdapter.class);

    private final RestClient restClient;
    private final LogGuardProperties properties;
    private final NotificationMessageFormatter formatter;

    public GoogleChatAdapter(RestClient.Builder restClientBuilder,
                             LogGuardProperties properties,
                             NotificationMessageFormatter formatter) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.formatter  = formatter;
    }

    @Override
    public DeliveryResult deliver(Notification notification) {
        try {
            String text = formatter.formatText(notification);
            post(text);
            return new DeliveryResult.Succeeded();
        } catch (Exception e) {
            log.error("Google Chat delivery failed: {}", e.getMessage());
            return new DeliveryResult.Failed(e.getMessage());
        }
    }

    @Override
    public void deliverDegradationAlert(int consecutiveFailures) {
        try {
            post(formatter.formatDegradationAlert(consecutiveFailures));
        } catch (Exception e) {
            log.error("Failed to send degradation alert to Google Chat: {}", e.getMessage());
        }
    }

    private void post(String text) {
        restClient.post()
                .uri(properties.googleChat().webhookUrl())
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();
    }
}
