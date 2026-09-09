package money.hejje.events.internal;

import money.hejje.events.EventProperties;
import money.hejje.events.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pulls the sources after boot and every morning before the session. */
@Component
class EventRefresh {

    private static final Logger log = LoggerFactory.getLogger(EventRefresh.class);

    private final EventService events;
    private final EventProperties props;

    EventRefresh(EventService events, EventProperties props) {
        this.events = events;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (props.enabled() && props.refreshOnStartup()) {
            Thread.ofVirtual().name("events-refresh").start(this::refresh);
        }
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
    void daily() {
        if (props.enabled()) {
            refresh();
        }
    }

    private void refresh() {
        try {
            EventService.RefreshResult r = events.refresh();
            log.info("Event calendar refreshed {}..{}: {} new, per source {}", r.from(), r.to(), r.inserted(), r.bySource());
        } catch (RuntimeException e) {
            log.warn("Event calendar refresh failed: {}", e.getMessage());
        }
    }
}
