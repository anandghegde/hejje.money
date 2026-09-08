package money.hejje.market;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Market data settings ({@code hejje.market.*}).
 *
 * @param watchlist       canonical Hejje symbols streamed in FULL mode by default
 * @param streamOnStartup open the broker stream and subscribe the watchlist once connected
 * @param staleAfter      a quote/candle older than this is stale; no tick for this long during the session is a gap
 * @param quoteStaleAfter QuoteCache staleness threshold
 * @param tickQueue       bounded tick-bus queue capacity (drop-oldest on overflow)
 * @param record          record ticks to Parquet under {@code data-dir/ticks}
 * @param retentionSessions Postgres candle retention in trading sessions
 * @param historicalPerSecond fallback historical request throttle until the M1.6 limiter exists
 */
@ConfigurationProperties("hejje.market")
public record MarketProperties(
        @DefaultValue({"INDEX:NIFTY 50", "INDEX:NIFTY BANK", "INDEX:INDIA VIX"}) List<String> watchlist,
        @DefaultValue("false") boolean streamOnStartup,
        @DefaultValue("10s") Duration staleAfter,
        @DefaultValue("5s") Duration quoteStaleAfter,
        @DefaultValue("100000") int tickQueue,
        @DefaultValue("false") boolean record,
        @DefaultValue("15") int retentionSessions,
        @DefaultValue("3") int historicalPerSecond) {
}
