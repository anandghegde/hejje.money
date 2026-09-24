package money.hejje.analogs.internal;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import money.hejje.analogs.AnalogsProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.ratings.WatchlistService;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Computes the session analogs at every checkpoint of the session for the instruments of the enabled deployments plus
 * the watchlist. Runs on simulation time in SIM.
 */
@Component
class SessionAnalogJob {

    private static final Logger log = LoggerFactory.getLogger(SessionAnalogJob.class);

    private final AnalogsProperties props;
    private final SessionAnalogComputer computer;
    private final StrategyService strategies;
    private final WatchlistService watchlist;
    private final HejjeClock clock;

    SessionAnalogJob(AnalogsProperties props, SessionAnalogComputer computer, StrategyService strategies, WatchlistService watchlist,
            HejjeClock clock) {
        this.props = props;
        this.computer = computer;
        this.strategies = strategies;
        this.watchlist = watchlist;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void checkpoints() {
        if (!props.enabled() || !clock.isSessionOpen()) {
            return;
        }
        LocalDate today = clock.today();
        for (String checkpoint : computer.due(today, clock.now())) {
            if (computer.computed(today, checkpoint)) {
                continue;
            }
            Set<UUID> targets = targets();
            if (targets.isEmpty()) {
                return;
            }
            try {
                List<String> written = computer.compute(today, checkpoint, targets);
                log.info("Session analogs {} {}: {} of {} instruments", today, checkpoint, written.size(), targets.size());
            } catch (RuntimeException e) {
                log.warn("Session analogs {} {} failed: {}", today, checkpoint, e.getMessage());
            }
        }
    }

    Set<UUID> targets() {
        Set<UUID> out = new LinkedHashSet<>();
        for (StrategyDeployment d : strategies.deployments(null, null, true)) {
            out.addAll(d.instrumentIds());
        }
        out.addAll(watchlist.instrumentIds());
        return out;
    }
}
