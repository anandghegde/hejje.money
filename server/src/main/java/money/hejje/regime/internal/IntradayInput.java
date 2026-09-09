package money.hejje.regime.internal;

import java.util.List;
import money.hejje.market.indicators.Bar;

/** The index's intraday bars of one session so far (closed bars only, in order; may be empty). */
record IntradayInput(List<Bar> bars, boolean sessionClosed) {

    static IntradayInput none() {
        return new IntradayInput(List.of(), false);
    }
}
