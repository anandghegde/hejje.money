package money.hejje.market.indicators;

/** Exponential moving average of closes seeded with the SMA of the first {@code n} closes (TA-Lib convention). */
public final class Ema extends AbstractIndicator {

    private final int n;
    private final double alpha;
    private final RollingWindow seed;
    private double ema = Double.NaN;

    public Ema(int n) {
        this.n = n;
        this.alpha = 2.0 / (n + 1);
        this.seed = new RollingWindow(n);
    }

    @Override
    protected double compute(Bar bar) {
        if (Double.isNaN(ema)) {
            seed.push(bar.close());
            if (seed.isFull()) {
                ema = seed.mean();
            }
            return ema;
        }
        ema = alpha * bar.close() + (1 - alpha) * ema;
        return ema;
    }

    public int period() {
        return n;
    }
}
