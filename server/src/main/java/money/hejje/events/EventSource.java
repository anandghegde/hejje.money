package money.hejje.events;

import java.time.LocalDate;
import java.util.List;

/** A pluggable calendar source. Each is behind its own flag; a failing source returns an empty list, never throws. */
public interface EventSource {

    /** Stable source name written into {@code market_event.source}. */
    String name();

    boolean enabled();

    /** Events in {@code [from, to]} (session dates, IST). */
    List<MarketEvent> events(LocalDate from, LocalDate to);
}
