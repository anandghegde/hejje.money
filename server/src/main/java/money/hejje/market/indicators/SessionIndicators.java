package money.hejje.market.indicators;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import money.hejje.common.time.HejjeClock;

/**
 * Indicators derived from the {@link SessionTracker}: today's open/high/low, previous-day levels, the central pivot range
 * (CPR), the narrow-range flag, gap % and session minutes.
 */
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

    /** Today's first-bar open. */
    static Indicator sessionOpen(SessionTracker tracker) {
        return of(tracker::sessionOpen);
    }

    /** Running high of today's bars so far (the current bar included). */
    static Indicator sessionHigh(SessionTracker tracker) {
        return of(tracker::sessionHigh);
    }

    /** Running low of today's bars so far (the current bar included). */
    static Indicator sessionLow(SessionTracker tracker) {
        return of(tracker::sessionLow);
    }

    /** Floor pivot from the previous session: P = (H + L + C) / 3. */
    static Indicator pivot(SessionTracker tracker) {
        return of(() -> pivotOf(tracker));
    }

    /** Upper edge of the central pivot range: max(TC, BC) with BC = (H + L) / 2 and TC = 2P − BC. */
    static Indicator cprTop(SessionTracker tracker) {
        return of(() -> Math.max(topCentral(tracker), bottomCentral(tracker)));
    }

    /** Lower edge of the central pivot range: min(TC, BC). */
    static Indicator cprBottom(SessionTracker tracker) {
        return of(() -> Math.min(topCentral(tracker), bottomCentral(tracker)));
    }

    /** CPR width as a percentage of the pivot: (top − bottom) / P × 100. */
    static Indicator cprWidthPct(SessionTracker tracker) {
        return of(() -> {
            double p = pivotOf(tracker);
            if (Double.isNaN(p) || p == 0) {
                return Double.NaN;
            }
            return Math.abs(topCentral(tracker) - bottomCentral(tracker)) / p * 100.0;
        });
    }

    /** 1 when the previous session's range is the narrowest of the last {@code n} sessions (NR7 = n 7), else 0. */
    static Indicator prevDayNr(SessionTracker tracker, int n) {
        return of(() -> tracker.prevDayNarrowestRange(n));
    }

    private static double pivotOf(SessionTracker t) {
        return (t.prevDayHigh() + t.prevDayLow() + t.prevDayClose()) / 3.0;
    }

    private static double bottomCentral(SessionTracker t) {
        return (t.prevDayHigh() + t.prevDayLow()) / 2.0;
    }

    private static double topCentral(SessionTracker t) {
        return 2 * pivotOf(t) - bottomCentral(t);
    }

    private static Indicator of(DoubleSupplier value) {
        return new AbstractIndicator() {
            @Override
            protected double compute(Bar bar) {
                return value.getAsDouble();
            }
        };
    }
}
