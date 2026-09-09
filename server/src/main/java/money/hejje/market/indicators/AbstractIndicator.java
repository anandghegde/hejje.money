package money.hejje.market.indicators;

import java.util.OptionalDouble;

/** Keeps a per-bar value history; subclasses compute the value (or NaN while warming up) for each bar. */
public abstract class AbstractIndicator implements Indicator {

    private final History history = new History();

    @Override
    public final void update(Bar bar) {
        history.push(compute(bar));
    }

    /** Value for this bar, or {@code NaN} when not ready. */
    protected abstract double compute(Bar bar);

    @Override
    public OptionalDouble value(int offset) {
        return history.get(offset);
    }
}
