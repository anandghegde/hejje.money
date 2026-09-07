package money.hejje.common.event;

import java.time.Instant;

/** Marker for high-volume market data events (ticks, candles). Delivered through {@link TickBus}, never persisted. */
public interface MarketEvent {

    Instant occurredAt();
}
