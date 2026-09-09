package money.hejje.backtest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Money;
import money.hejje.common.Side;

/**
 * One simulated round trip.
 *
 * @param rMultiple   net P&L divided by the money risked at entry (|entry − initial stop| × qty)
 * @param evidence    the entry conditions with observed values, as {condition, status, lhs, rhs}
 */
public record BacktestTrade(UUID id, UUID backtestId, UUID instrumentId, Split split, Instant entryTime, Instant exitTime, Side side,
        int qty, BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal stop, BigDecimal target, Money grossPnl, Money costs, Money netPnl,
        double rMultiple, ExitReason exitReason, List<Map<String, Object>> evidence) {

    public BacktestTrade {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public long holdingMinutes() {
        return Duration.between(entryTime, exitTime).toMinutes();
    }

    public boolean isWin() {
        return netPnl.paise() > 0;
    }
}
