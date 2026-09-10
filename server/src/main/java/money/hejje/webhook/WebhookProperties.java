package money.hejje.webhook;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Webhook settings ({@code hejje.webhooks.*}, docs/webhooks.md).
 *
 * @param replayWindow       how far the sender's timestamp may be from the server clock
 * @param signalValidity     how long an external signal stays actionable
 * @param defaultRiskRupees  sizing of MANUAL_EXTERNAL intents without {@code riskRupees}
 */
@ConfigurationProperties("hejje.webhooks")
public record WebhookProperties(@DefaultValue("PT5M") Duration replayWindow, @DefaultValue("PT5M") Duration signalValidity,
        @DefaultValue("2000") BigDecimal defaultRiskRupees) {
}
