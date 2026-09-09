package money.hejje.market.indicators;

import java.time.Duration;
import money.hejje.common.time.HejjeClock;

/** Indicators derived from the {@link SessionTracker}: previous-day levels, gap % and session minutes. */
final class SessionIndicators {

    private SessionIndicators() {
    }

    static Indicator prevDayHigh(SessionTracker tracker) {
        return new AbstractIndicator() {
            @Override
            protected double compute(Bar bar) {
                return tracker.prevDayHigh();
            }
        };
    }

    static Indicator prevDayLow(SessionTracker tracker) {
        return new AbstractIndicator() {
            @Override
            protected double compute(Bar bar) {
                return tracker.prevDayLow();
            }
        };
    }

    static Indicator prevDayClose(SessionTracker tracker) {
        return new AbstractIndicator() {
            @Override
            protected double compute(Bar bar) {
                return tracker.prevDayClose();
            }
        };
    }

    /** (session open − previous close) / previous close × 100. */
    static Indicator gapPct(SessionTracker tracker) {
        return new AbstractIndicator() {
            @Override
            protected double compute(Bar bar) {
                double prev = tracker.prevDayClose();
                double open = tracker.sessionOpen();
                if (Double.isNaN(prev) || Double.isNaN(open) || prev == 0) {
                    return Double.NaN;
                }
                return (open - prev) / prev * 100.0;
            }
        };
    }

    /** Whole minutes from 09:15 to the bar's close. */
    static Indicator sessionMinutes() {
        return new AbstractIndicator() {
            @Override
            protected double compute(Bar bar) {
                return Duration.between(HejjeClock.SESSION_OPEN, bar.closeTimeOfDay()).toMinutes();
            }
        };
    }
}
