package money.hejje.ratings.internal;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import money.hejje.market.Candle;

/** One instrument's D1 history as arrays, oldest first. {@code day} is the epoch day of the session (IST). */
record DailySeries(long[] day, double[] open, double[] high, double[] low, double[] close, double[] volume) {

    static DailySeries of(List<Candle> candles, ZoneId zone) {
        int n = candles.size();
        DailySeries s = new DailySeries(new long[n], new double[n], new double[n], new double[n], new double[n], new double[n]);
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            s.day[i] = c.openTime().atZone(zone).toLocalDate().toEpochDay();
            s.open[i] = c.open().doubleValue();
            s.high[i] = c.high().doubleValue();
            s.low[i] = c.low().doubleValue();
            s.close[i] = c.close().doubleValue();
            s.volume[i] = c.volume();
        }
        return s;
    }

    int size() {
        return day.length;
    }

    /** Index of the candle of {@code date}, or -1 when the instrument has none that day. */
    int indexOf(LocalDate date) {
        int i = Arrays.binarySearch(day, date.toEpochDay());
        return i < 0 ? -1 : i;
    }

    /** Index of the last candle on or before {@code date}, or -1. */
    int indexAtOrBefore(LocalDate date) {
        int i = Arrays.binarySearch(day, date.toEpochDay());
        return i >= 0 ? i : -i - 2;
    }

    LocalDate date(int i) {
        return LocalDate.ofEpochDay(day[i]);
    }
}
