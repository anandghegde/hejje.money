package money.hejje.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Money;

/**
 * Everything the risk engine needs about the account right now (PRD section 31 inputs). Assembled by
 * {@code AccountSnapshotBuilder}; the engine is a pure function of the intent, this snapshot and the limits.
 *
 * @param realizedPnl        realized P&L today
 * @param unrealizedPnl      mark-to-market of open positions
 * @param openPositionCount  number of non-flat positions
 * @param grossExposure      sum of |net qty| * last price across positions
 * @param tradesToday        fills recorded today
 * @param consecutiveLosses  consecutive losing closed positions today
 * @param availableCash      broker available cash
 * @param usedMargin         broker used margin
 * @param netPositionQty     signed net quantity per instrument (for averaging-down / opposite-side detection)
 * @param lastTradeAt        last fill time per instrument (for the re-entry cooldown)
 */
public record AccountSnapshot(
        Money realizedPnl,
        Money unrealizedPnl,
        int openPositionCount,
        Money grossExposure,
        int tradesToday,
        int consecutiveLosses,
        Money availableCash,
        Money usedMargin,
        Map<UUID, Integer> netPositionQty,
        Map<UUID, Instant> lastTradeAt) {

    public Money totalPnl() {
        return realizedPnl.plus(unrealizedPnl);
    }
}
