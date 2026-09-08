package money.hejje.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Product;
import money.hejje.common.Side;

/** One execution (fill) reported by the broker. */
public record BrokerTrade(
        String brokerTradeId,
        String brokerOrderId,
        UUID instrumentId,
        String tradingSymbol,
        String exchangeSegment,
        Side side,
        Product product,
        int quantity,
        BigDecimal price,
        Instant ts,
        Map<String, Object> raw) {

    public BrokerTrade {
        raw = raw == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
    }
}
