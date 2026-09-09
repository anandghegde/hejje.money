package money.hejje.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.Money;
import money.hejje.common.Side;

/**
 * One completed round trip rebuilt from fills (entry side accumulated until the net quantity returned to zero).
 *
 * @param strategyId null for manual trades (attributed to {@code MANUAL})
 * @param versionId  the strategy version when the entry order came from a signal, else null
 */
public record RoundTrip(UUID instrumentId, UUID strategyId, UUID versionId, UUID signalId, UUID entryOrderId, Side side, int quantity, BigDecimal entryPrice,
        BigDecimal exitPrice, Instant openedAt, Instant closedAt, Money grossPnl, Money fees) {

    public Money netPnl() {
        return grossPnl.minus(fees);
    }

    public boolean isWin() {
        return netPnl().paise() > 0;
    }

    public long holdingMinutes() {
        return java.time.Duration.between(openedAt, closedAt).toMinutes();
    }
}
