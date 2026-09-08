package money.hejje.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Product;

/** A net position keyed by (mode, instrument, product, strategy). Average-cost book; realized P&L in paise. */
public record Position(
        UUID id,
        ExecutionMode mode,
        UUID instrumentId,
        Product product,
        UUID strategyId,
        int netQuantity,
        BigDecimal averagePrice,
        Money realizedPnl,
        int dayBuyQty,
        int daySellQty,
        Instant openedAt,
        Instant updatedAt) {

    public boolean isFlat() {
        return netQuantity == 0;
    }
}
