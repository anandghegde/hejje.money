package money.hejje.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A REST quote snapshot. */
public record Quote(UUID instrumentId, Instant ts, BigDecimal lastPrice, BigDecimal bid, BigDecimal ask, long volume, long oi,
        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
}
