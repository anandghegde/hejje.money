package money.hejje.market.indicators;

import java.time.LocalDate;

/**
 * Session-anchored VWAP: cumulative typical price × volume over cumulative volume since the first bar of the
 * session (reset when the session date changes). Not ready while the session volume is zero (index series).
 */
public final class SessionVwap extends AbstractIndicator {

    private LocalDate session;
    private double pv;
    private double volume;

    @Override
    protected double compute(Bar bar) {
        if (!bar.session().equals(session)) {
            session = bar.session();
            pv = 0;
            volume = 0;
        }
        pv += bar.typicalPrice() * bar.volume();
        volume += bar.volume();
        return volume > 0 ? pv / volume : Double.NaN;
    }
}
