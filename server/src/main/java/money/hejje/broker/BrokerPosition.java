package money.hejje.broker;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Money;
import money.hejje.common.Product;

/** A net position as reported by the broker. */
public record BrokerPosition(
        UUID instrumentId,
        String tradingSymbol,
        String exchangeSegment,
        Product product,
        int netQuantity,
        BigDecimal averagePrice,
        int dayBuyQuantity,
        int daySellQuantity,
        BigDecimal dayBuyValue,
        BigDecimal daySellValue,
        Money realizedPnl,
        Money unrealizedPnl,
        BigDecimal lastPrice,
        Map<String, Object> raw) {

    public BrokerPosition {
        raw = raw == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
    }
}
