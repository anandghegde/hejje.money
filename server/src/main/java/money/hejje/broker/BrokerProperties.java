package money.hejje.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Broker module settings ({@code hejje.broker.*}).
 *
 * @param adapter which adapter is active: {@code fake} or {@code zerodha}
 * @param webUrl  base URL of the Web client; the login callback redirects to {@code <webUrl>/broker?connected=1}
 */
@ConfigurationProperties("hejje.broker")
public record BrokerProperties(@DefaultValue("fake") String adapter, @DefaultValue("http://localhost:5173") String webUrl) {
}
