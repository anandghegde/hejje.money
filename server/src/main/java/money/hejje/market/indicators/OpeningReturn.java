package money.hejje.market.indicators;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import money.hejje.common.time.HejjeClock;

/**
 * Return from the previous session's close to the close of the bar ending at 09:15 + {@code duration}, in percent.
 * Not ready before that bar has closed (or when the previous close is unknown); constant for the rest of the session.
 * When the bar ending exactly then is missing, the first bar closing after it is used.
 */
public final class OpeningReturn extends AbstractIndicator {

    private final LocalTime end;
    private final SessionTracker tracker;
    private LocalDate session;
    private double value;

    public OpeningReturn(Duration duration, SessionTracker tracker) {
        this.end = HejjeClock.SESSION_OPEN.plus(duration);
        this.tracker = tracker;
    }

    @Override
    protected double compute(Bar bar) {
        if (!bar.session().equals(session)) {
            session = bar.session();
            value = Double.NaN;
        }
        if (Double.isNaN(value) && !bar.closeTimeOfDay().isBefore(end)) {
            double prev = tracker.prevDayClose();
            value = Double.isNaN(prev) || prev == 0 ? Double.NaN : (bar.close() - prev) / prev * 100.0;
        }
        return value;
    }
}
