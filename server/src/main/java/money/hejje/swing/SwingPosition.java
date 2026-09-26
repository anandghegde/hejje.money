package money.hejje.swing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import money.hejje.common.ExecutionMode;

/**
 * One delivery round trip of the swing book (plan M11.1), open until the CNC position returns to zero.
 *
 * @param positionId   the orders module's position (mode, instrument, CNC, strategy) it belongs to
 * @param entryPrice   the average entry price of the quantity held
 * @param initialStop  the stop at entry (from the entry order's intent), the R unit; null when none was given
 * @param goal         the target at entry, or null
 * @param holdingDays  sessions held after the entry session, once closed
 * @param trail        whether the stop trails (breakeven at +1R, then under the 20-day low; plan M11.2)
 */
public record SwingPosition(UUID id, ExecutionMode mode, UUID positionId, UUID instrumentId, UUID strategyId, UUID entryOrderId, Instant openedAt,
        LocalDate entryDate, int quantity, BigDecimal entryPrice, BigDecimal initialStop, BigDecimal goal, Status status, Instant closedAt,
        LocalDate exitDate, BigDecimal exitPrice, Integer holdingDays, boolean trail) {

    public enum Status { OPEN, CLOSED }
}
