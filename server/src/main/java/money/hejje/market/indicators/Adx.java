package money.hejje.market.indicators;

/**
 * Wilder's ADX (TA-Lib convention): smoothed +DM, -DM and TR are seeded with plain sums over the first {@code n - 1}
 * changes and Wilder-smoothed from the n-th change on, so DX starts on bar {@code n + 1}; ADX is the simple mean of
 * the first {@code n} DX values (bar {@code 2n}) followed by Wilder smoothing.
 */
public final class Adx extends AbstractIndicator {

    private final int n;
    private double prevHigh = Double.NaN;
    private double prevLow = Double.NaN;
    private double prevClose = Double.NaN;
    private int changes;
    private double smoothedPlusDm;
    private double smoothedMinusDm;
    private double smoothedTr;
    private int dxCount;
    private double dxSum;
    private double adx = Double.NaN;

    public Adx(int n) {
        this.n = n;
    }

    @Override
    protected double compute(Bar bar) {
        if (Double.isNaN(prevClose)) {
            remember(bar);
            return Double.NaN;
        }
        double upMove = bar.high() - prevHigh;
        double downMove = prevLow - bar.low();
        double plusDm = upMove > downMove && upMove > 0 ? upMove : 0;
        double minusDm = downMove > upMove && downMove > 0 ? downMove : 0;
        double tr = Atr.trueRange(bar, prevClose);
        remember(bar);
        changes++;
        if (changes < n) {
            // TA-Lib seeds the smoothed sums with the first n-1 raw values and smooths from the n-th change on
            smoothedPlusDm += plusDm;
            smoothedMinusDm += minusDm;
            smoothedTr += tr;
            return Double.NaN;
        }
        smoothedPlusDm = smoothedPlusDm - smoothedPlusDm / n + plusDm;
        smoothedMinusDm = smoothedMinusDm - smoothedMinusDm / n + minusDm;
        smoothedTr = smoothedTr - smoothedTr / n + tr;
        double dx;
        if (smoothedTr == 0) {
            dx = 0;
        } else {
            double plusDi = 100.0 * smoothedPlusDm / smoothedTr;
            double minusDi = 100.0 * smoothedMinusDm / smoothedTr;
            double sum = plusDi + minusDi;
            dx = sum == 0 ? 0 : 100.0 * Math.abs(plusDi - minusDi) / sum;
        }
        if (Double.isNaN(adx)) {
            dxSum += dx;
            dxCount++;
            if (dxCount < n) {
                return Double.NaN;
            }
            adx = dxSum / n;
            return adx;
        }
        adx = (adx * (n - 1) + dx) / n;
        return adx;
    }

    private void remember(Bar bar) {
        prevHigh = bar.high();
        prevLow = bar.low();
        prevClose = bar.close();
    }
}
