package money.hejje.market.internal;

import money.hejje.common.time.HejjeClock;
import money.hejje.market.MarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly prune of Postgres candles older than the retention window (long-term history stays in Parquet). */
@Component
class CandleRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(CandleRetentionJob.class);

    private final MarketCandleStore store;
    private final HejjeClock clock;
    private final MarketProperties properties;

    CandleRetentionJob(MarketCandleStore store, HejjeClock clock, MarketProperties properties) {
        this.store = store;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(cron = "0 30 1 * * *", zone = "Asia/Kolkata")
    void prune() {
        java.time.LocalDate cutoff = clock.today();
        int sessions = properties.retentionSessions();
        while (sessions > 0) {
            cutoff = cutoff.minusDays(1);
            if (clock.isTradingDay(cutoff)) {
                sessions--;
            }
        }
        int removed = store.pruneBefore(cutoff);
        if (removed > 0) {
            log.info("Pruned {} candle rows older than {}", removed, cutoff);
        }
    }
}
