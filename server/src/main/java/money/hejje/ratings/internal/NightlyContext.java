package money.hejje.ratings.internal;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import money.hejje.common.ClientNotification;
import money.hejje.ratings.Base;
import money.hejje.ratings.DailyCandlesRefreshed;
import money.hejje.ratings.DailyDigest;
import money.hejje.ratings.DailyRating;
import money.hejje.ratings.RatingsProperties;
import money.hejje.ratings.RatingsService;
import money.hejje.ratings.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The nightly chain off {@link DailyCandlesRefreshed}: the day's ratings, then the bases, then the setup alerts for
 * Leaders and the watchlist. A failure is logged; nothing in the trading core depends on any of it.
 */
@Component
class NightlyContext {

    private static final Logger log = LoggerFactory.getLogger(NightlyContext.class);

    private final RatingsProperties props;
    private final RatingsService ratings;
    private final ApplicationEventPublisher events;
    private final WatchlistService watchlist;
    private final DailyDigest digest;
    /** With the analogs on, their nightly run comes last and publishes the digest with its ranked list. */
    private final boolean analogsFollow;

    NightlyContext(RatingsProperties props, RatingsService ratings, ApplicationEventPublisher events, WatchlistService watchlist, DailyDigest digest,
            @org.springframework.beans.factory.annotation.Value("${hejje.analogs.enabled:false}") boolean analogsFollow) {
        this.digest = digest;
        this.analogsFollow = analogsFollow;
        this.watchlist = watchlist;
        this.events = events;
        this.props = props;
        this.ratings = ratings;
    }

    /** Ordered before the analogs' listener of the same event ({@code AnalogsNightly}, order 200): their digest reads the day's base transitions. */
    @EventListener
    @Order(100)
    void on(DailyCandlesRefreshed event) {
        if (!props.enabled()) {
            return;
        }
        try {
            ratings.compute(event.date(), event.date());
            ratings.computeBases(event.date(), event.date());
            alerts(event.date());
            if (!analogsFollow) {
                digest.publish(event.date(), java.util.List.of());
            }
        } catch (RuntimeException e) {
            log.warn("Nightly ratings for {} failed: {}", event.date(), e.getMessage());
        }
    }

    /** The day's status changes of Leaders and watchlist symbols as {@code setup} client events (the notification rules pick them up). */
    void alerts(LocalDate date) {
        Set<UUID> leaders = ratings.ratings(date).stream().filter(ratings::leader).map(DailyRating::instrumentId).collect(Collectors.toCollection(java.util.HashSet::new));
        leaders.addAll(watchlist.instrumentIds());
        for (Base b : ratings.transitions(date)) {
            String event = switch (b.status()) {
                case IN_BUY_ZONE -> date.equals(b.triggerDate()) ? "ENTERED_BUY_ZONE" : null;
                case NEAR_PIVOT -> "NEAR_PIVOT";
                case STOPPED -> "SETUP_STOPPED";
                case HIT_GOAL -> "SETUP_HIT_GOAL";
                default -> null;
            };
            if (event != null && leaders.contains(b.instrumentId())) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("event", event);
                data.put("symbol", b.symbol());
                data.put("type", b.type().name());
                data.put("date", date.toString());
                data.put("pivot", b.pivot().toPlainString());
                data.put("buyHigh", b.buyHigh().toPlainString());
                data.put("stop", b.stop().toPlainString());
                data.put("goal", b.goal().toPlainString());
                data.put("volumeConfirmed", b.volumeConfirmed());
                data.put("outcomeR", b.outcomeR());
                events.publishEvent(new ClientNotification("setup", data));
            }
        }
    }
}
