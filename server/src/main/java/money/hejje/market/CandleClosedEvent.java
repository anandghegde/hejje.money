package money.hejje.market;

import java.time.Instant;
import money.hejje.common.event.MarketEvent;

/** Published on the {@code TickBus} when a candle closes (not a durable domain event). */
public record CandleClosedEvent(Candle candle) implements MarketEvent {

    @Override
    public Instant occurredAt() {
        return candle.openTime().plus(candle.timeframe().duration());
    }
}
