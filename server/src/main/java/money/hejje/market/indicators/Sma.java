package money.hejje.market.indicators;

/** Simple moving average of closes; ready after {@code n} bars. */
public final class Sma extends AbstractIndicator {

    private final RollingWindow window;

    public Sma(int n) {
        this.window = new RollingWindow(n);
    }

    @Override
    protected double compute(Bar bar) {
        window.push(bar.close());
        return window.isFull() ? window.mean() : Double.NaN;
    }
}
