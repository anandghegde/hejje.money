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
 * @param horizon    SWING for delivery (CNC) fills, else INTRADAY (plan M11.1); the two never share a round trip
 */
public record RoundTrip(UUID instrumentId, UUID strategyId, UUID versionId, UUID signalId, UUID entryOrderId, Side side, int quantity, BigDecimal entryPrice,
        BigDecimal exitPrice, Instant openedAt, Instant closedAt, Money grossPnl, Money fees, Horizon horizon) {

    public RoundTrip(UUID instrumentId, UUID strategyId, UUID versionId, UUID signalId, UUID entryOrderId, Side side, int quantity, BigDecimal entryPrice,
            BigDecimal exitPrice, Instant openedAt, Instant closedAt, Money grossPnl, Money fees) {
        this(instrumentId, strategyId, versionId, signalId, entryOrderId, side, quantity, entryPrice, exitPrice, openedAt, closedAt, grossPnl, fees, Horizon.INTRADAY);
    }

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
