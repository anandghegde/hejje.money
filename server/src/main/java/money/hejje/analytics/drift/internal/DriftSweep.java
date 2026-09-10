package money.hejje.analytics.drift.internal;

import money.hejje.analytics.drift.DriftService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-evaluates every enabled deployment on {@code hejje.drift.interval} (trades age out of the window between reviews). */
@Component
class DriftSweep {

    private final DriftService drift;

    DriftSweep(DriftService drift) {
        this.drift = drift;
    }

    @Scheduled(fixedDelayString = "${hejje.drift.interval:PT10M}", initialDelayString = "PT2M")
    void sweep() {
        drift.evaluateAll();
    }
}
