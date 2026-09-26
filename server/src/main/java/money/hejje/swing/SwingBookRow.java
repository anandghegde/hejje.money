package money.hejje.swing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import money.hejje.common.Money;

/**
 * One open position of the swing book as {@code GET /swing/positions} shows it (plan M11.1).
 *
 * @param daysHeld      sessions since the entry session
 * @param stop          the protective stop now in force: the GTT's stop, else the entry stop
 * @param lastPrice     the last traded price, or the entry price when there is none
 * @param r             open P&L per unit over the entry risk per unit (entry − initial stop); null without a stop
 * @param unrealizedPnl (last − entry) × quantity
 * @param gtt           the broker-side stop: ACTIVE, MISSING or NONE (plan M11.2)
 * @param gttId         the broker's GTT id, or null
 */
public record SwingBookRow(UUID id, UUID instrumentId, String symbol, UUID strategyId, Instant openedAt, LocalDate entryDate, int daysHeld, int quantity,
        BigDecimal entryPrice, BigDecimal stop, BigDecimal goal, BigDecimal lastPrice, BigDecimal r, Money unrealizedPnl, String gtt, String gttId) {
}
