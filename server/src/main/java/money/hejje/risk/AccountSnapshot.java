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
 * @param entriesToday       the first fill time of every order that opened or added to a position today (plan M9.7)
 * @param closesToday        every round trip closed today with its realized P&L, in time order (plan M9.7)
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
        Map<UUID, Instant> lastTradeAt,
        java.util.List<Instant> entriesToday,
        java.util.List<Close> closesToday) {

    /** A round trip closed today. */
    public record Close(Instant at, BigDecimal realized) {}

    public AccountSnapshot {
        entriesToday = entriesToday == null ? java.util.List.of() : java.util.List.copyOf(entriesToday);
        closesToday = closesToday == null ? java.util.List.of() : java.util.List.copyOf(closesToday);
    }

    public AccountSnapshot(Money realizedPnl, Money unrealizedPnl, int openPositionCount, Money grossExposure, int tradesToday, int consecutiveLosses,
            Money availableCash, Money usedMargin, Map<UUID, Integer> netPositionQty, Map<UUID, Instant> lastTradeAt) {
        this(realizedPnl, unrealizedPnl, openPositionCount, grossExposure, tradesToday, consecutiveLosses, availableCash, usedMargin, netPositionQty,
                lastTradeAt, null, null);
    }

    public Money totalPnl() {
        return realizedPnl.plus(unrealizedPnl);
    }
}
