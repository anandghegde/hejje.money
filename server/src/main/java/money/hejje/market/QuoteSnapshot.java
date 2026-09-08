package money.hejje.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Last known quote for one instrument, with a staleness flag relative to a reference time. */
public record QuoteSnapshot(UUID instrumentId, Instant ts, BigDecimal lastPrice, BigDecimal bid, BigDecimal ask, long volume,
        long oi, boolean stale) {
}
