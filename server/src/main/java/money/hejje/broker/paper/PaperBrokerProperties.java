package money.hejje.broker.paper;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paper trading settings ({@code hejje.paper.*}).
 *
 * @param slippageBps            MARKET fills slip this many basis points against the taker
 * @param partialFillProbability chance a resting fill is split into two (0 disables)
 * @param startingCapital        simulated cash in rupees
 */
@ConfigurationProperties("hejje.paper")
public record PaperBrokerProperties(
        @DefaultValue("5") BigDecimal slippageBps,
        @DefaultValue("0.0") double partialFillProbability,
        @DefaultValue("1000000") long startingCapital) {
}
