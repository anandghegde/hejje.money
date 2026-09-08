package money.hejje.broker;

import java.math.BigDecimal;
import java.time.Instant;

/** One historical candle from the broker. {@code openTime} is the candle start. */
public record BrokerCandle(Instant openTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume, long oi) {
}
