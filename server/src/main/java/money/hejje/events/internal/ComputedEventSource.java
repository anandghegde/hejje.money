package money.hejje.events.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import money.hejje.common.Exchange;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.HolidayCalendar;
import money.hejje.events.EventProperties;
import money.hejje.events.EventScope;
import money.hejje.events.EventSource;
import money.hejje.events.EventType;
import money.hejje.events.MarketEvent;
import money.hejje.instruments.InstrumentService;
import org.springframework.stereotype.Component;

/** NSE holidays (holiday calendar), F&O expiries (option expiries of each configured underlying) and configured index rebalance dates. */
@Component
class ComputedEventSource implements EventSource {

    private final EventProperties props;
    private final HolidayCalendar holidays;
    private final InstrumentService instruments;
    private final HejjeClock clock;

    ComputedEventSource(EventProperties props, HolidayCalendar holidays, InstrumentService instruments, HejjeClock clock) {
        this.props = props;
        this.holidays = holidays;
        this.instruments = instruments;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "computed";
    }

    @Override
    public boolean enabled() {
        return props.computed().enabled();
    }

    @Override
    public List<MarketEvent> events(LocalDate from, LocalDate to) {
        List<MarketEvent> out = new ArrayList<>();
        var now = clock.now();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() < 6 && holidays.isHoliday(d, Exchange.NSE)) {
                out.add(Events.on(EventType.HOLIDAY, EventScope.MARKET, null, null, "NSE holiday", d, null, null, name(), 1.0, Map.of(), clock.zone(), now));
            }
        }
        for (String underlying : props.computed().expiryUnderlyings()) {
            String u = underlying.trim().toUpperCase(Locale.ROOT);
            TreeMap<LocalDate, Boolean> expiries = new TreeMap<>();
            for (LocalDate e : instruments.weeklyExpiries(u, from)) {
                expiries.put(e, Boolean.FALSE);
            }
            // the last expiry of a calendar month is the monthly one
            for (LocalDate e : expiries.keySet()) {
                LocalDate next = expiries.higherKey(e);
                if (next == null || next.getMonth() != e.getMonth()) {
                    expiries.put(e, Boolean.TRUE);
                }
            }
            for (Map.Entry<LocalDate, Boolean> e : expiries.entrySet()) {
                if (e.getKey().isBefore(from) || e.getKey().isAfter(to)) {
                    continue;
                }
                String title = u + (e.getValue() ? " monthly expiry" : " weekly expiry");
                out.add(Events.on(EventType.FNO_EXPIRY, EventScope.MARKET, null, u, title, e.getKey(), null, null, name(), 1.0,
                        Map.of("underlying", u, "monthly", e.getValue()), clock.zone(), now));
            }
        }
        for (LocalDate d : props.computed().indexRebalanceDates()) {
            if (!d.isBefore(from) && !d.isAfter(to)) {
                out.add(Events.on(EventType.INDEX_REBALANCE, EventScope.MARKET, null, null, "Index rebalance", d, null, null, name(), 0.8, Map.of(), clock.zone(), now));
            }
        }
        return out;
    }
}
