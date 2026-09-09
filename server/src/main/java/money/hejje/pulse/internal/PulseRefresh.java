package money.hejje.pulse.internal;

import money.hejje.common.time.HejjeClock;
import money.hejje.pulse.PulseProperties;
import money.hejje.pulse.PulseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recomputes and stores the pulse every {@code hejje.pulse.interval} while the session is open. */
@Component
class PulseRefresh {

    private static final Logger log = LoggerFactory.getLogger(PulseRefresh.class);

    private final PulseService pulse;
    private final PulseProperties props;
    private final HejjeClock clock;

    PulseRefresh(PulseService pulse, PulseProperties props, HejjeClock clock) {
        this.pulse = pulse;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hejje.pulse.interval:PT1M}", initialDelayString = "PT1M")
    void refresh() {
        if (!props.enabled() || !clock.isSessionOpen()) {
            return;
        }
        try {
            pulse.snapshotNow(true);
        } catch (RuntimeException e) {
            log.warn("Pulse refresh failed: {}", e.getMessage());
        }
    }
}
