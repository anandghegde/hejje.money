package money.hejje.market.indicators;

import java.util.OptionalDouble;

/** An incremental indicator over closed bars. Values are readable only for bars that have been fed. */
public interface Indicator {

    /** Consumes the next closed bar (strictly in time order). */
    void update(Bar bar);

    /** Value at {@code offset} closed bars back (0 = the most recent), empty while warming up. */
    OptionalDouble value(int offset);

    default OptionalDouble value() {
        return value(0);
    }

    /** True when the most recent bar has a value. */
    default boolean isReady() {
        return value(0).isPresent();
    }
}
