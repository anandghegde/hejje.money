package money.hejje.market.indicators;

/** One Bollinger band: SMA(n) of closes plus or minus {@code k} population standard deviations. */
public final class Bollinger extends AbstractIndicator {

    private final RollingWindow window;
    private final double k;
    private final boolean upper;

    public Bollinger(int n, double k, boolean upper) {
        this.window = new RollingWindow(n);
        this.k = k;
        this.upper = upper;
    }

    @Override
    protected double compute(Bar bar) {
        window.push(bar.close());
        if (!window.isFull()) {
            return Double.NaN;
        }
        double sd = window.populationStdDev();
        return upper ? window.mean() + k * sd : window.mean() - k * sd;
    }
}
