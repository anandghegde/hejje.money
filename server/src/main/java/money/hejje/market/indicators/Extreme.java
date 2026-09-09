package money.hejje.market.indicators;

/** Highest high or lowest low over the last {@code n} bars including the current one. */
public final class Extreme extends AbstractIndicator {

    private final RollingWindow window;
    private final boolean highest;

    public Extreme(int n, boolean highest) {
        this.window = new RollingWindow(n);
        this.highest = highest;
    }

    @Override
    protected double compute(Bar bar) {
        window.push(highest ? bar.high() : bar.low());
        if (!window.isFull()) {
            return Double.NaN;
        }
        return highest ? window.max() : window.min();
    }
}
