package money.hejje.ratings.internal;

import java.time.LocalDate;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.UniverseBackfill;
import money.hejje.market.UniverseHistory;
import money.hejje.ratings.DailyCandlesRefreshed;
import money.hejje.ratings.RatingsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The evening D1 refresh (plan M8.1): brings the universe's daily candles current, then publishes
 * {@link DailyCandlesRefreshed}. The event is published even when some symbols failed (their rows are simply missing
 * that day), but not when nothing at all could be fetched.
 */
@Component
class DailyRefresh {

    private static final Logger log = LoggerFactory.getLogger(DailyRefresh.class);

    private final RatingsProperties props;
    private final UniverseHistory history;
    private final HejjeClock clock;
    private final ApplicationEventPublisher events;

    DailyRefresh(RatingsProperties props, UniverseHistory history, HejjeClock clock, ApplicationEventPublisher events) {
        this.props = props;
        this.history = history;
        this.clock = clock;
        this.events = events;
    }

    @Scheduled(cron = "${hejje.ratings.daily-refresh-cron:0 30 18 * * MON-FRI}", zone = "Asia/Kolkata")
    void scheduled() {
        if (!props.enabled() || !clock.isTradingDay(clock.today())) {
            return;
        }
        try {
            refresh(props.refreshSessions());
        } catch (RuntimeException e) {
            log.warn("Evening D1 refresh failed: {}", e.getMessage());
        }
    }

    UniverseBackfill refresh(int sessions) {
        LocalDate date = clock.today();
        UniverseBackfill result = history.refreshDaily(props.universe(), sessions);
        log.info("D1 refresh of {}: {} of {} symbols, {} candles, {} failed, {} unresolved", result.universe(), result.childrenDone(),
                result.childrenTotal(), result.candlesWritten(), result.childrenFailed(), result.unresolved().size());
        if (result.childrenDone() > 0) {
            events.publishEvent(new DailyCandlesRefreshed(date));
        }
        return result;
    }
}
