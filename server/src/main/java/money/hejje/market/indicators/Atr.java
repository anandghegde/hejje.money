package money.hejje.market.indicators;

/**
 * Wilder's average true range. True range needs a previous close, so the first TR is on bar 2; the first ATR is the
 * simple mean of the first {@code n} true ranges (bar {@code n + 1}), then Wilder smoothing (TA-Lib convention).
 */
public final class Atr extends AbstractIndicator {

    private final int n;
    private double previousClose = Double.NaN;
    private int count;
    private double sum;
    private double atr = Double.NaN;

    public Atr(int n) {
        this.n = n;
    }

    @Override
    protected double compute(Bar bar) {
        if (Double.isNaN(previousClose)) {
            previousClose = bar.close();
            return Double.NaN;
        }
        double tr = trueRange(bar, previousClose);
        previousClose = bar.close();
        if (Double.isNaN(atr)) {
            sum += tr;
            count++;
            if (count < n) {
                return Double.NaN;
            }
            atr = sum / n;
            return atr;
        }
        atr = (atr * (n - 1) + tr) / n;
        return atr;
    }

    static double trueRange(Bar bar, double previousClose) {
        return Math.max(bar.high() - bar.low(), Math.max(Math.abs(bar.high() - previousClose), Math.abs(bar.low() - previousClose)));
    }
}
