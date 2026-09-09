package money.hejje.regime.internal;

import java.time.LocalDate;
import java.util.List;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.regime.RegimeProperties;
import money.hejje.regime.RegimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the store current: an intraday snapshot every {@code hejje.regime.intraday-snapshot} during the session, the
 * final label just after the close, and (on startup) labels for sessions in the daily history that have none under the
 * current classifier version (which is how a version bump relabels the past).
 */
@Component
class RegimeRefresh {

    private static final Logger log = LoggerFactory.getLogger(RegimeRefresh.class);

    private final RegimeService regime;
    private final RegimeProperties props;
    private final HejjeClock clock;

    RegimeRefresh(RegimeService regime, RegimeProperties props, HejjeClock clock) {
        this.regime = regime;
        this.props = props;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void labelMissingOnStartup() {
        if (!props.enabled() || !props.labelOnStartup()) {
            return;
        }
        Thread.ofVirtual().name("regime-label").start(() -> {
            try {
                LocalDate today = clock.today();
                List<Candle> daily = regime.indexDaily(today.minusYears(5), today.minusDays(1));
                if (daily.isEmpty()) {
                    return;
                }
                LocalDate from = daily.get(0).openTime().atZone(clock.zone()).toLocalDate();
                LocalDate to = daily.get(daily.size() - 1).openTime().atZone(clock.zone()).toLocalDate();
                int missing = regime.missingLabels(from, to);
                if (missing == 0) {
                    return;
                }
                log.info("Labelling {} sessions without a regime label (classifier v{}) between {} and {}", missing, props.classifierVersion(), from, to);
                var result = regime.labelHistory(from, to);
                log.info("Regime labelling done: {} of {} sessions, hash {}", result.labelled(), result.sessions(), result.hash());
            } catch (RuntimeException e) {
                log.warn("Startup regime labelling failed: {}", e.getMessage());
            }
        });
    }

    @Scheduled(fixedDelayString = "${hejje.regime.intraday-snapshot:PT5M}", initialDelayString = "PT1M")
    void intradaySnapshot() {
        if (!props.enabled() || !clock.isSessionOpen()) {
            return;
        }
        try {
            regime.snapshotNow(true);
        } catch (RuntimeException e) {
            log.warn("Intraday regime snapshot failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    void finalLabel() {
        if (!props.enabled() || !clock.isTradingDay(clock.today())) {
            return;
        }
        try {
            regime.snapshotNow(true);
        } catch (RuntimeException e) {
            log.warn("Final regime label failed: {}", e.getMessage());
        }
    }
}
