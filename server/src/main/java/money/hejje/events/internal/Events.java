package money.hejje.events.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.EventScope;
import money.hejje.events.EventType;
import money.hejje.events.MarketEvent;

/** Builders shared by the sources. */
public final class Events {

    private Events() {
    }

    /** An event on {@code date}; all-day when {@code time} is null (starts at the session open, ends at the close). */
    public static MarketEvent on(EventType type, EventScope scope, UUID instrumentId, String symbol, String title, LocalDate date, LocalTime time, LocalDate endDate,
            String source, double confidence, Map<String, Object> raw, ZoneId zone, Instant importedAt) {
        boolean allDay = time == null;
        Instant starts = date.atTime(allDay ? HejjeClock.SESSION_OPEN : time).atZone(zone).toInstant();
        Instant ends = allDay ? (endDate == null ? date : endDate).atTime(HejjeClock.SESSION_CLOSE).atZone(zone).toInstant()
                : endDate == null ? null : endDate.atTime(HejjeClock.SESSION_CLOSE).atZone(zone).toInstant();
        String key = key(type, scope, symbol, date, title);
        return new MarketEvent(Ids.newId(), type, scope, instrumentId, symbol, title, starts, ends, allDay, source, key, confidence, raw, importedAt);
    }

    static String key(EventType type, EventScope scope, String symbol, LocalDate date, String title) {
        return type + "|" + scope + "|" + (symbol == null ? "" : symbol.toUpperCase(Locale.ROOT)) + "|" + date + "|" + title.trim().toLowerCase(Locale.ROOT);
    }
}
