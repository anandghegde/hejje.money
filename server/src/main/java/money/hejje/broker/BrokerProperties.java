package money.hejje.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Broker module settings ({@code hejje.broker.*}).
 *
 * @param adapter which adapter is active: {@code fake}, {@code zerodha} or {@code dhan}
 * @param webUrl  base URL of the Web client; the login callback redirects to {@code <webUrl>/broker?connected=1}
 * @param rateLimits per-broker limits by adapter code (plan M5.6); an adapter without an entry uses {@code limits}
 */
@ConfigurationProperties("hejje.broker")
public record BrokerProperties(@DefaultValue("fake") String adapter, @DefaultValue("http://localhost:5173") String webUrl,
        @DefaultValue Limits limits, java.util.Map<String, Limits> rateLimits) {

    public BrokerProperties {
        rateLimits = rateLimits == null ? java.util.Map.of() : java.util.Map.copyOf(rateLimits);
    }

    /** The limits of one broker (its {@code rate-limits} entry, else the global {@code limits}). */
    public Limits limitsFor(String broker) {
        return rateLimits.getOrDefault(broker, limits);
    }

    /**
     * Per-operation broker rate limits (PRD section 39). Transactional operations fail fast when exhausted; reads wait
     * briefly. All configuration-driven, never hardcoded in strategy logic.
     *
     * @param ordersPerSecond   order place/modify/cancel per second
     * @param ordersPerMinute   order operations per minute
     * @param ordersPerDay      order operations per day
     * @param quotePerSecond    quote requests per second
     * @param historicalPerSecond historical requests per second
     * @param generalPerSecond  everything else per second
     * @param readWaitMillis    how long a read may wait for a token before failing
     */
    public record Limits(
            @DefaultValue("10") int ordersPerSecond,
            @DefaultValue("200") int ordersPerMinute,
            @DefaultValue("3000") int ordersPerDay,
            @DefaultValue("1") int quotePerSecond,
            @DefaultValue("3") int historicalPerSecond,
            @DefaultValue("10") int generalPerSecond,
            @DefaultValue("1000") long readWaitMillis) {
    }
}
