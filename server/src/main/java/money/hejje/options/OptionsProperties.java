package money.hejje.options;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Options settings ({@code hejje.options.*}, docs/options.md).
 *
 * @param riskFreeRate     annual rate for Black-76 discounting
 * @param defaultVolatility volatility assumed for delta-based strike selection when a strike has no implied volatility
 * @param maxLots          lots per option order (risk control)
 * @param maxPremiumRupees premium at risk per option buy (risk control)
 * @param expiryDayCutoff  no new option positions on their expiry day from this IST time
 * @param minPaperTrades   closed paper options positions a version needs before LIVE
 * @param monitorInterval  how often open options positions are checked against their exits
 * @param underlyings      index name → option underlying (signals on an index map to its options)
 */
@ConfigurationProperties("hejje.options")
public record OptionsProperties(
        @DefaultValue("0.065") double riskFreeRate,
        @DefaultValue("0.15") double defaultVolatility,
        @DefaultValue("10") int maxLots,
        @DefaultValue("50000") long maxPremiumRupees,
        @DefaultValue("13:00") LocalTime expiryDayCutoff,
        @DefaultValue("30") int minPaperTrades,
        @DefaultValue("PT5S") Duration monitorInterval,
        Map<String, String> underlyings) {

    public OptionsProperties {
        underlyings = underlyings == null || underlyings.isEmpty() ? Map.of("NIFTY 50", "NIFTY", "NIFTY BANK", "BANKNIFTY", "NIFTY FIN SERVICE", "FINNIFTY")
                : Map.copyOf(underlyings);
    }
}
