package money.hejje.market.indicators;

import java.time.LocalDate;

/**
 * Tracks the current session's open/high/low/close and rolls them into "previous day" values when the session date
 * changes. Shared by the previous-day and gap indicators of one context. Values come from the intraday bars fed to the
 * context, so a previous session is known after one full session of warm-up (or after {@link #seedPreviousDay}).
 */
public final class SessionTracker {

    private LocalDate session;
    private double open = Double.NaN;
    private double high = Double.NaN;
    private double low = Double.NaN;
    private double close = Double.NaN;
    private double prevHigh = Double.NaN;
    private double prevLow = Double.NaN;
    private double prevClose = Double.NaN;

    /** Seeds the previous day's values from a daily candle (used when warm-up history starts mid-way). */
    public void seedPreviousDay(LocalDate day, double high, double low, double close) {
        this.session = day;
        this.high = high;
        this.low = low;
        this.close = close;
        this.open = Double.NaN;
    }

    void update(Bar bar) {
        if (!bar.session().equals(session)) {
            if (session != null) {
                prevHigh = high;
                prevLow = low;
                prevClose = close;
            }
            session = bar.session();
            open = bar.open();
            high = bar.high();
            low = bar.low();
        } else {
            high = Math.max(high, bar.high());
            low = Math.min(low, bar.low());
        }
        close = bar.close();
    }

    public double sessionOpen() {
        return open;
    }

    public double prevDayHigh() {
        return prevHigh;
    }

    public double prevDayLow() {
        return prevLow;
    }

    public double prevDayClose() {
        return prevClose;
    }

    public LocalDate session() {
        return session;
    }
}
