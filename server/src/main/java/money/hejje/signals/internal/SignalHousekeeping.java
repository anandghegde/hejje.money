package money.hejje.signals.internal;

import money.hejje.signals.SignalEngine;
import money.hejje.signals.SignalProperties;
import money.hejje.signals.SignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Starts the engine after the application is ready (after the execution bootstrap) and expires stale signals. */
@Component
class SignalHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(SignalHousekeeping.class);

    private final SignalEngine engine;
    private final SignalService signals;
    private final SignalProperties properties;

    SignalHousekeeping(SignalEngine engine, SignalService signals, SignalProperties properties) {
        this.engine = engine;
        this.signals = signals;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    void onReady() {
        if (!properties.enabled()) {
            log.info("Signal engine disabled (hejje.signals.enabled=false)");
            return;
        }
        try {
            engine.start();
        } catch (RuntimeException e) {
            log.error("Signal engine failed to start", e);
        }
    }

    @Scheduled(fixedDelayString = "${hejje.signals.expiry-sweep:30s}")
    void expire() {
        int n = signals.expireStale();
        if (n > 0) {
            log.info("Expired {} stale signal(s)", n);
        }
    }
}
