package money.hejje.analogs.internal;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import money.hejje.analogs.AnalogComputeResult;
import money.hejje.analogs.AnalogsProperties;
import money.hejje.analogs.AnalogsService;
import money.hejje.common.time.HejjeClock;
import money.hejje.ratings.DailyCandlesRefreshed;
import money.hejje.ratings.DailyDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The nightly daily-analog run (after the ratings, which listen to the same event with a lower order value) and the match retention. */
@Component
class AnalogsNightly {

    private static final Logger log = LoggerFactory.getLogger(AnalogsNightly.class);

    private final AnalogsProperties props;
    private final DailyAnalogComputer daily;
    private final AnalogStore store;
    private final HejjeClock clock;
    private final AnalogsService analogs;
    private final DailyDigest digest;

    AnalogsNightly(AnalogsProperties props, DailyAnalogComputer daily, AnalogStore store, HejjeClock clock, AnalogsService analogs, DailyDigest digest) {
        this.analogs = analogs;
        this.digest = digest;
        this.props = props;
        this.daily = daily;
        this.store = store;
        this.clock = clock;
    }

    /** After the ratings' listener (order 100): a listener without an order would have the lowest precedence, not the highest. */
    @EventListener
    @Order(200)
    void on(DailyCandlesRefreshed event) {
        if (!props.enabled()) {
            return;
        }
        try {
            AnalogComputeResult result = daily.compute(List.of(event.date()), Set.of(), List.of());
            digest.publish(event.date(), top(event.date()));
            if (Duration.ofMillis(result.millis()).compareTo(props.nightlyBudget()) > 0) {
                log.warn("Nightly analogs took {} ms, over the budget of {}: cut lookbacks in config/analogs.yaml", result.millis(), props.nightlyBudget());
            }
        } catch (RuntimeException e) {
            log.warn("Nightly analogs for {} failed: {}", event.date(), e.getMessage());
        }
    }

    /** The five best win rates of the 15-session lookback over the narrative forward window, each with its count. */
    List<String> top(java.time.LocalDate date) {
        int lookback = props.lookbacks().contains(15) ? 15 : props.lookbacks().get(0);
        return analogs.rank(lookback, props.narrativeForward(), "winRate", props.minEvidence(), date).stream().limit(5)
                .map(r -> String.format(java.util.Locale.ROOT, "%s %.0f %% of %d", r.symbol(), r.outcome().winRate() * 100, r.outcome().count())).toList();
    }

    @Scheduled(cron = "0 15 4 * * *", zone = "Asia/Kolkata")
    void pruneMatches() {
        if (props.enabled()) {
            int deleted = store.pruneMatches(clock.today().minusDays(props.matchRetentionDays()));
            if (deleted > 0) {
                log.info("Pruned {} analog match documents older than {} days", deleted, props.matchRetentionDays());
            }
        }
    }
}
