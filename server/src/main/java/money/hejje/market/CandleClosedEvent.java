package money.hejje.market;

import java.time.Instant;
import money.hejje.common.event.MarketEvent;

/**
 * Published on the {@code TickBus} when a candle closes (not a durable domain event). {@code micro} is the bar's
 * order-book and flow data when its ticks carried any (plan M9.4), else null.
 */
public record CandleClosedEvent(Candle candle, BarMicro micro) implements MarketEvent {

    public CandleClosedEvent(Candle candle) {
        this(candle, null);
    }

    @Override
    public Instant occurredAt() {
        return candle.openTime().plus(candle.timeframe().duration());
    }
}
