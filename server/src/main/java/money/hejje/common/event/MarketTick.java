package money.hejje.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Normalized market tick (PRD section 45). Prices are {@code BigDecimal}; zero means "not available" for bid/ask.
 *
 * @param instrumentId Hejje instrument id
 * @param ts           exchange timestamp when known, otherwise receive time
 * @param lastPrice    last traded price (or index value)
 * @param bid          best bid, or null
 * @param ask          best ask, or null
 * @param volume       cumulative volume traded today
 * @param oi           open interest (derivatives), 0 otherwise
 * @param mode         subscription depth the tick came from
 */
public record MarketTick(UUID instrumentId, Instant ts, BigDecimal lastPrice, BigDecimal bid, BigDecimal ask, long volume,
        long oi, Mode mode) implements MarketEvent {

    public enum Mode { LTP, QUOTE, FULL }

    public MarketTick {
        if (instrumentId == null || ts == null || lastPrice == null) {
            throw new IllegalArgumentException("MarketTick needs instrumentId, ts and lastPrice");
        }
        mode = mode == null ? Mode.LTP : mode;
    }

    public static MarketTick ltp(UUID instrumentId, Instant ts, BigDecimal lastPrice) {
        return new MarketTick(instrumentId, ts, lastPrice, null, null, 0, 0, Mode.LTP);
    }

    @Override
    public Instant occurredAt() {
        return ts;
    }
}
