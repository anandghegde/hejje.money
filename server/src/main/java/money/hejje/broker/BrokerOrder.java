package money.hejje.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.Validity;

/**
 * An order as the broker sees it, normalized.
 *
 * @param brokerOrderId   broker order id
 * @param instrumentId    Hejje instrument, or null when the broker symbol is not in the instrument master
 * @param tradingSymbol   broker trading symbol
 * @param exchangeSegment broker exchange code
 * @param side            BUY or SELL
 * @param quantity        ordered units
 * @param filledQuantity  executed units so far
 * @param pendingQuantity units still open
 * @param averagePrice    average fill price (0 when nothing filled)
 * @param orderType       order type
 * @param product         product
 * @param limitPrice      limit price or 0
 * @param triggerPrice    trigger price or 0
 * @param validity        validity
 * @param status          normalized status
 * @param rawStatus       broker status string
 * @param statusMessage   broker message (rejection reason etc.)
 * @param tag             client tag echoed by the broker
 * @param parentOrderId   parent order for multi-leg varieties, else null
 * @param placedAt        broker order timestamp
 * @param updatedAt       exchange update timestamp when known
 * @param raw             broker fields for diagnostics
 */
public record BrokerOrder(
        String brokerOrderId,
        UUID instrumentId,
        String tradingSymbol,
        String exchangeSegment,
        Side side,
        int quantity,
        int filledQuantity,
        int pendingQuantity,
        BigDecimal averagePrice,
        OrderType orderType,
        Product product,
        BigDecimal limitPrice,
        BigDecimal triggerPrice,
        Validity validity,
        BrokerOrderStatus status,
        String rawStatus,
        String statusMessage,
        String tag,
        String parentOrderId,
        Instant placedAt,
        Instant updatedAt,
        Map<String, Object> raw) {

    public BrokerOrder {
        raw = raw == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
    }

    public BrokerOrderRef ref() {
        return new BrokerOrderRef(brokerOrderId);
    }
}
