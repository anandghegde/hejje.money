package money.hejje.ratings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A detected base with its trade plan and its status as of a session. The pattern and the plan never change after
 * detection; only the status part does. The plan is informational: nothing in Hejje trades it.
 *
 * @param startDate        the left high (a reversal: the reversal session)
 * @param detectedDate     the session on which the pattern first qualified
 * @param depthPct         left high to the base low, in percent
 * @param baseLow          the lowest low of the base (a reversal: the session's low, which is also the stop)
 * @param status           as of {@code statusDate}
 * @param triggerDate      first close at or above the pivot, null before that
 * @param entry            assumed entry: the pivot, or the trigger session's open when it opened above the pivot
 * @param volumeConfirmed  the trigger session's volume was at least 1.4 x its 50-session average
 * @param exit             price at which a triggered setup closed (goal, stop, a gap open through either, or the close at expiry)
 * @param outcomePct       realised outcome of a closed, triggered setup in percent
 * @param outcomeR         ... and in multiples of the planned risk {@code entry - stop}
 */
public record Base(UUID id, UUID instrumentId, String symbol, BaseType type, String engineVersion, LocalDate startDate, LocalDate detectedDate,
        double depthPct, BigDecimal baseLow, BigDecimal pivot, BigDecimal buyLow, BigDecimal buyHigh, BigDecimal stop, BigDecimal goal,
        Map<String, Object> evidence, BaseStatus status, LocalDate statusDate, LocalDate triggerDate, BigDecimal entry, Boolean volumeConfirmed,
        BigDecimal exit, Double outcomePct, Double outcomeR) {

    public Base withStatus(BaseStatus status, LocalDate statusDate, LocalDate triggerDate, BigDecimal entry, Boolean volumeConfirmed,
            BigDecimal exit, Double outcomePct, Double outcomeR) {
        return new Base(id, instrumentId, symbol, type, engineVersion, startDate, detectedDate, depthPct, baseLow, pivot, buyLow, buyHigh, stop, goal,
                evidence, status, statusDate, triggerDate, entry, volumeConfirmed, exit, outcomePct, outcomeR);
    }
}
