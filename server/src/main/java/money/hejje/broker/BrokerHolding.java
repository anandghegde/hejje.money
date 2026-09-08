package money.hejje.broker;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** A delivery holding as reported by the broker. */
public record BrokerHolding(UUID instrumentId, String tradingSymbol, String exchangeSegment, String isin, int quantity,
        BigDecimal averagePrice, BigDecimal lastPrice, Map<String, Object> raw) {

    public BrokerHolding {
        raw = raw == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
    }
}
