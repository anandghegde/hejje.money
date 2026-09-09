package money.hejje.regime.internal;

import java.time.LocalDate;

/**
 * Indicator state of the daily series as of one session: trend inputs from the index, volatility percentiles from
 * VIX closes and the index ATR ratio. {@code NaN} where an input is not ready.
 *
 * @param sessions      daily bars consumed so far (including this one)
 * @param atr           the daily ATR as of the previous session (used to normalise intraday ranges; NaN when unknown)
 * @param prevClose     the previous session's close (NaN for the first bar)
 */
record DailyState(LocalDate date, int sessions, double open, double close, double emaFast, double emaSlow, double emaFastBack, double adx, double atr,
        double prevClose, double vix, Double vixPercentile, Double atrRatioPercentile, int percentileWindow) {
}
