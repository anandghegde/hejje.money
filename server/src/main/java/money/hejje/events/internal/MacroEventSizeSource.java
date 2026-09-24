package money.hejje.events.internal;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import money.hejje.events.EventScope;
import money.hejje.events.EventService;
import money.hejje.events.MarketEvent;
import money.hejje.risk.SizeFactorSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The day's market-wide macro event for the risk-event size cut (plan M9.7): any market-scope event of a macro type on
 * the date (RBI, Fed, CPI, budget, election, geopolitical), from the calendar or from headlines ({@code news-jev}).
 */
@Component
class MacroEventSizeSource implements SizeFactorSource {

    private final EventService events;

    MacroEventSizeSource(@Lazy EventService events) {
        this.events = events;
    }

    @Override
    public Optional<String> macroEvent(LocalDate date) {
        return events.marketEvents(date).stream().filter(e -> e.scope() == EventScope.MARKET && e.type().isMacro())
                .sorted(Comparator.comparing(MarketEvent::startsAt)).map(e -> e.type().name() + ": " + e.title() + " (" + e.source() + ")").findFirst();
    }
}
