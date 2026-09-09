package money.hejje.pulse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import money.hejje.common.time.HejjeClock;
import money.hejje.pulse.internal.PulseEngine;
import money.hejje.pulse.internal.PulseStore;
import money.hejje.regime.RegimeService;
import org.springframework.stereotype.Service;

/** Public API of the pulse module: the current pulse (cached for {@code hejje.pulse.interval}) and the stored history of a session. */
@Service
public class PulseService {

    private final PulseEngine engine;
    private final PulseStore store;
    private final RegimeService regime;
    private final PulseProperties props;
    private final HejjeClock clock;
    private volatile PulseSnapshot cached;

    PulseService(PulseEngine engine, PulseStore store, RegimeService regime, PulseProperties props, HejjeClock clock) {
        this.engine = engine;
        this.store = store;
        this.regime = regime;
        this.props = props;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public PulseSnapshot current() {
        Instant now = clock.now();
        PulseSnapshot c = cached;
        if (c != null && c.date().equals(regime.sessionDate(now)) && !c.asOf().plus(props.interval()).isBefore(now)) {
            return c;
        }
        return snapshotNow(false);
    }

    /** Computes the pulse as of now (and stores it when asked); never throws into callers. */
    public PulseSnapshot snapshotNow(boolean persist) {
        Instant now = clock.now();
        LocalDate date = regime.sessionDate(now);
        PulseSnapshot snapshot;
        if (!props.enabled()) {
            snapshot = disabled(date, now, "pulse disabled (hejje.pulse.enabled=false)");
        } else {
            try {
                snapshot = engine.snapshot(date, now);
            } catch (RuntimeException e) {
                snapshot = disabled(date, now, "pulse inputs unavailable: " + e.getMessage());
            }
        }
        cached = snapshot;
        if (persist && props.enabled()) {
            store.insert(snapshot);
        }
        return snapshot;
    }

    public List<PulseSnapshot> history(LocalDate date) {
        return store.history(date);
    }

    private static PulseSnapshot disabled(LocalDate date, Instant now, String reason) {
        return new PulseSnapshot(date, now, new TechnicalPulse(PulseDirection.NEUTRAL, PulseStrength.WEAK, 0, 0, List.of(), List.of(reason)),
                new MarketPulse("Unknown", "Unknown", "Unknown", List.of(), "NEUTRAL"));
    }
}
