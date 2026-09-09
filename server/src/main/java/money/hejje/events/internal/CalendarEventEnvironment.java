package money.hejje.events.internal;

import java.time.LocalDate;
import java.util.List;
import money.hejje.events.EventProperties;
import money.hejje.events.EventService;
import money.hejje.events.MarketEvent;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.EventEnvironmentSource;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The regime's event environment from the calendar: BUDGET > RBI > FED > other macro (MACRO_EVENT_SESSION) >
 * EARNINGS_HEAVY (≥ {@code earnings-heavy-count} results events) > EXPIRY_SESSION > NORMAL; UNKNOWN when the module is off.
 */
@Component
@Primary
class CalendarEventEnvironment implements EventEnvironmentSource {

    private final EventService events;
    private final EventProperties props;

    CalendarEventEnvironment(EventService events, EventProperties props) {
        this.events = events;
        this.props = props;
    }

    @Override
    public EventEnvironment environment(LocalDate session) {
        if (!props.enabled()) {
            return EventEnvironment.UNKNOWN;
        }
        List<MarketEvent> all;
        try {
            all = events.events(session, session, null, true);
        } catch (RuntimeException e) {
            return EventEnvironment.UNKNOWN;
        }
        boolean budget = false;
        boolean rbi = false;
        boolean fed = false;
        boolean macro = false;
        boolean expiry = false;
        int results = 0;
        for (MarketEvent e : all) {
            switch (e.type()) {
                case BUDGET -> budget = true;
                case RBI_POLICY -> rbi = true;
                case FED_DECISION -> fed = true;
                case FNO_EXPIRY -> expiry = true;
                case RESULTS, EARNINGS_CALL -> results++;
                default -> {
                    if (e.type().isMacro()) {
                        macro = true;
                    }
                }
            }
        }
        if (budget) {
            return EventEnvironment.BUDGET;
        }
        if (rbi) {
            return EventEnvironment.RBI;
        }
        if (fed) {
            return EventEnvironment.FED;
        }
        if (macro) {
            return EventEnvironment.MACRO_EVENT_SESSION;
        }
        if (results >= props.risk().earningsHeavyCount()) {
            return EventEnvironment.EARNINGS_HEAVY;
        }
        if (expiry) {
            return EventEnvironment.EXPIRY_SESSION;
        }
        return EventEnvironment.NORMAL;
    }
}
