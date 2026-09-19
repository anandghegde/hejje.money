package money.hejje.market.indicators;

/**
 * The standard Supertrend line. Basic bands are {@code hl2 ± k × ATR(n)} (Wilder ATR, same warm-up as {@link Atr});
 * the final upper band only moves down (and the final lower band only up) unless the previous close broke through it.
 * The line is the final lower band in an uptrend and the final upper band in a downtrend; an uptrend flips down on a
 * close below the final lower band, a downtrend flips up on a close above the final upper band. The first ready bar
 * starts in an uptrend. Continuous across sessions, like {@link Ema}.
 */
public final class Supertrend extends AbstractIndicator {

    private final Atr atr;
    private final double k;
    private double previousClose = Double.NaN;
    private double upper = Double.NaN;
    private double lower = Double.NaN;
    private boolean up = true;

    public Supertrend(int n, double k) {
        this.atr = new Atr(n);
        this.k = k;
    }

    @Override
    protected double compute(Bar bar) {
        atr.update(bar);
        double close = previousClose;
        previousClose = bar.close();
        if (atr.value().isEmpty()) {
            return Double.NaN;
        }
        double hl2 = (bar.high() + bar.low()) / 2.0;
        double basicUpper = hl2 + k * atr.value().getAsDouble();
        double basicLower = hl2 - k * atr.value().getAsDouble();
        if (Double.isNaN(upper)) {
            upper = basicUpper;
            lower = basicLower;
            up = true;
            return lower;
        }
        upper = basicUpper < upper || close > upper ? basicUpper : upper;
        lower = basicLower > lower || close < lower ? basicLower : lower;
        up = up ? bar.close() >= lower : bar.close() > upper;
        return up ? lower : upper;
    }
}
