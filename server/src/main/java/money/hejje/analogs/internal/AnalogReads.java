package money.hejje.analogs.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import money.hejje.analogs.AnalogKind;
import money.hejje.analogs.AnalogMatch;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsProperties;
import money.hejje.common.time.HejjeClock;
import org.springframework.stereotype.Component;

/** Reads under the current engine version, capped at what the Hejje clock allows to be seen. */
@Component
public class AnalogReads {

    private final AnalogStore store;
    private final AnalogsProperties props;
    private final HejjeClock clock;

    AnalogReads(AnalogStore store, AnalogsProperties props, HejjeClock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    /**
     * The session to read for {@code requested} (null: the latest). A daily summary is a function of its session's
     * close, so it is visible only once that session has closed; a session summary is a function of the session up to
     * its checkpoint, so today's are visible.
     */
    public Optional<LocalDate> session(AnalogKind kind, LocalDate requested) {
        LocalDate today = clock.today();
        boolean closed = !clock.isTradingDay(today) || !clock.now().isBefore(clock.sessionWindow(today).close().toInstant());
        LocalDate cap = kind == AnalogKind.SESSION || closed ? today : today.minusDays(1);
        return store.latest(requested == null || requested.isAfter(cap) ? cap : requested, kind, props.engineVersion());
    }

    public Optional<AnalogSummary> find(String symbol, LocalDate date, AnalogKind kind, int lookback, String checkpoint) {
        return store.find(symbol, date, kind, lookback, checkpoint, props.engineVersion());
    }

    public Optional<List<AnalogMatch>> matches(String symbol, LocalDate date, AnalogKind kind, int lookback, String checkpoint) {
        return store.matches(symbol, date, kind, lookback, checkpoint, props.engineVersion());
    }

    public List<AnalogSummary> forDate(LocalDate date, AnalogKind kind) {
        return store.forDate(date, kind, props.engineVersion());
    }
}
