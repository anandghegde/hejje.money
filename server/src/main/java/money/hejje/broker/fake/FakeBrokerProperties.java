package money.hejje.broker.fake;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Fake broker settings ({@code hejje.broker.fake.*}).
 *
 * @param connected       start with a connected session (no login needed in dev)
 * @param startingCapital simulated cash in rupees
 */
@ConfigurationProperties("hejje.broker.fake")
public record FakeBrokerProperties(@DefaultValue("true") boolean connected, @DefaultValue("1000000") long startingCapital) {
}
