package money.hejje.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One calendar event.
 *
 * @param instrumentId the instrument (INSTRUMENT scope), null for market events
 * @param symbol       display symbol: the Hejje symbol of the instrument, or the underlying / index for market events (may be null)
 * @param startsAt     start (for all-day events: the IST session open of the day)
 * @param endsAt       end, or null
 * @param source       {@code computed | curated | csv | manual | nse}
 * @param externalKey  deterministic key within the source used to upsert (no duplicates on refresh)
 * @param confidence   0..1 how certain the date/time is
 * @param raw          the source's original fields
 */
public record MarketEvent(UUID id, EventType type, EventScope scope, UUID instrumentId, String symbol, String title, Instant startsAt, Instant endsAt,
        boolean allDay, String source, String externalKey, double confidence, Map<String, Object> raw, Instant importedAt) {

    public MarketEvent {
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }
}
