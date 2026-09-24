package money.hejje.llm.internal;

import java.time.Duration;
import money.hejje.common.time.HejjeClock;
import money.hejje.llm.JevProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes sent states older than {@code hejje.jev.state-retention-days}; answers are kept for calibration. */
@Component
class JevRetention {

    private static final Logger log = LoggerFactory.getLogger(JevRetention.class);

    private final JevStore store;
    private final JevProperties props;
    private final HejjeClock clock;

    JevRetention(JevStore store, JevProperties props, HejjeClock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 19 * * *", zone = "Asia/Kolkata")
    void prune() {
        int n = store.pruneStates(clock.now().minus(Duration.ofDays(Math.max(1, props.stateRetentionDays()))));
        if (n > 0) {
            log.info("Pruned {} stored Jev states", n);
        }
    }
}
