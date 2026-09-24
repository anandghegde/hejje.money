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
 * @param bidQty5      sum of the quantities of the five best bids (FULL mode with depth), else null (plan M9.4)
 * @param askQty5      sum of the quantities of the five best asks, else null
 * @param totalBuyQty  the exchange's total pending buy quantity for the day (FULL mode), else null
 * @param totalSellQty the exchange's total pending sell quantity for the day, else null
 */
public record MarketTick(UUID instrumentId, Instant ts, BigDecimal lastPrice, BigDecimal bid, BigDecimal ask, long volume,
        long oi, Mode mode, Long bidQty5, Long askQty5, Long totalBuyQty, Long totalSellQty) implements MarketEvent {

    public enum Mode { LTP, QUOTE, FULL }

    public MarketTick {
        if (instrumentId == null || ts == null || lastPrice == null) {
            throw new IllegalArgumentException("MarketTick needs instrumentId, ts and lastPrice");
        }
        mode = mode == null ? Mode.LTP : mode;
    }

    /** A tick without order-book data (LTP and QUOTE modes, replays of old files, feeds without depth). */
    public MarketTick(UUID instrumentId, Instant ts, BigDecimal lastPrice, BigDecimal bid, BigDecimal ask, long volume, long oi, Mode mode) {
        this(instrumentId, ts, lastPrice, bid, ask, volume, oi, mode, null, null, null, null);
    }

    /** True when the tick carries any order-book field. */
    public boolean hasBook() {
        return bidQty5 != null || askQty5 != null || totalBuyQty != null || totalSellQty != null;
    }

    public static MarketTick ltp(UUID instrumentId, Instant ts, BigDecimal lastPrice) {
        return new MarketTick(instrumentId, ts, lastPrice, null, null, 0, 0, Mode.LTP);
    }

    @Override
    public Instant occurredAt() {
        return ts;
    }
}
