package money.hejje.broker;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * A delivery holding as reported by the broker. {@code quantity} is settled; {@code t1Quantity} was bought on the previous
 * session and settles today (T+1, plan M11.1). Today's delivery buys and sells are positions (product CNC), not holdings.
 */
public record BrokerHolding(UUID instrumentId, String tradingSymbol, String exchangeSegment, String isin, int quantity, int t1Quantity,
        BigDecimal averagePrice, BigDecimal lastPrice, Map<String, Object> raw) {

    public BrokerHolding {
        raw = raw == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
    }

    public BrokerHolding(UUID instrumentId, String tradingSymbol, String exchangeSegment, String isin, int quantity, BigDecimal averagePrice,
            BigDecimal lastPrice, Map<String, Object> raw) {
        this(instrumentId, tradingSymbol, exchangeSegment, isin, quantity, 0, averagePrice, lastPrice, raw);
    }

    /** Settled plus T1 quantity: what the account holds from earlier sessions. */
    public int totalQuantity() {
        return quantity + t1Quantity;
    }
}
