package be.vdab.logguard.infrastructure.googlechat;

import be.vdab.logguard.domain.port.out.TerminalOutputPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Guards the delivery switch: with {@code logguard.google-chat.enabled=true} (the value inherited from the
 * main {@code application.yml}), the {@code @Primary} {@link GoogleChatOutputAdapter} — not the terminal
 * adapter — must be the {@link TerminalOutputPort} that {@code PollService} receives.
 */
@SpringBootTest(properties = "logguard.google-chat.enabled=true")
class GoogleChatWiringTest {

    @Autowired
    private TerminalOutputPort terminalOutputPort;

    @Test
    void googleChatAdapterIsThePrimaryOutputPortWhenEnabled() {
        assertInstanceOf(GoogleChatOutputAdapter.class, terminalOutputPort,
                "with google-chat enabled the primary TerminalOutputPort must be the Google Chat adapter");
    }
}
