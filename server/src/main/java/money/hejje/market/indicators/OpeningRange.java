package money.hejje.market.indicators;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import money.hejje.common.time.HejjeClock;

/**
 * High or low of the first {@code range} minutes of the session (bars whose open time is before 09:15 + range).
 * Not ready until the bar closing at or after 09:15 + range has closed; then constant for the rest of the session.
 */
public final class OpeningRange extends AbstractIndicator {

    private final LocalTime rangeEnd;
    private final boolean high;
    private LocalDate session;
    private double extreme;
    private boolean complete;

    public OpeningRange(Duration range, boolean high) {
        this.rangeEnd = HejjeClock.SESSION_OPEN.plus(range);
        this.high = high;
    }

    @Override
    protected double compute(Bar bar) {
        if (!bar.session().equals(session)) {
            session = bar.session();
            extreme = high ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            complete = false;
        }
        if (complete) {
            return extreme;
        }
        if (bar.openTimeOfDay().isBefore(rangeEnd)) {
            extreme = high ? Math.max(extreme, bar.high()) : Math.min(extreme, bar.low());
        }
        if (!bar.closeTimeOfDay().isBefore(rangeEnd) && Double.isFinite(extreme)) {
            complete = true;
            return extreme;
        }
        return Double.NaN;
    }
}
