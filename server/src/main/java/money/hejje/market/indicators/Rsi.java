package money.hejje.market.indicators;

/**
 * Wilder's RSI over closes: the first average gain/loss is the simple mean of the first {@code n} changes (so the
 * first value appears on bar {@code n + 1}), then {@code avg = (avg * (n - 1) + change) / n}.
 */
public final class Rsi extends AbstractIndicator {

    private final int n;
    private double previousClose = Double.NaN;
    private int changes;
    private double gainSum;
    private double lossSum;
    private double avgGain = Double.NaN;
    private double avgLoss = Double.NaN;

    public Rsi(int n) {
        this.n = n;
    }

    @Override
    protected double compute(Bar bar) {
        double close = bar.close();
        if (Double.isNaN(previousClose)) {
            previousClose = close;
            return Double.NaN;
        }
        double change = close - previousClose;
        previousClose = close;
        double gain = Math.max(change, 0);
        double loss = Math.max(-change, 0);
        if (Double.isNaN(avgGain)) {
            gainSum += gain;
            lossSum += loss;
            changes++;
            if (changes < n) {
                return Double.NaN;
            }
            avgGain = gainSum / n;
            avgLoss = lossSum / n;
        } else {
            avgGain = (avgGain * (n - 1) + gain) / n;
            avgLoss = (avgLoss * (n - 1) + loss) / n;
        }
        if (avgGain + avgLoss == 0) {
            return 0.0; // TA-Lib convention for a flat window
        }
        return 100.0 * avgGain / (avgGain + avgLoss);
    }
}
