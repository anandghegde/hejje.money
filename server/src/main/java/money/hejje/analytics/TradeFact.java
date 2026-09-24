package money.hejje.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One closed round trip with its review context (plan M4.5): money in paise, the entry's regime/trend, event risk and
 * news bias, how it exited, slippage and rule adherence. {@code hour} is the IST entry hour. {@code cause} and
 * {@code entryTiming} are the review's trade cause (plan M9.6; UNKNOWN until classified).
 */
public record TradeFact(UUID entryOrderId, UUID instrumentId, String instrument, UUID strategyId, String strategy, String family, Instant openedAt,
        Instant closedAt, String side, int quantity, BigDecimal entryPrice, BigDecimal exitPrice, long grossPaise, long feesPaise, long netPaise, Double outcomeR,
        String regime, String trend, String event, String news, String exitReason, Double entrySlippageBps, Double exitSlippageBps, Integer adherencePct,
        Boolean setupValid, int hour, String cause, String entryTiming) {

    public TradeFact(UUID entryOrderId, UUID instrumentId, String instrument, UUID strategyId, String strategy, String family, Instant openedAt,
            Instant closedAt, String side, int quantity, BigDecimal entryPrice, BigDecimal exitPrice, long grossPaise, long feesPaise, long netPaise,
            Double outcomeR, String regime, String trend, String event, String news, String exitReason, Double entrySlippageBps, Double exitSlippageBps,
            Integer adherencePct, Boolean setupValid, int hour) {
        this(entryOrderId, instrumentId, instrument, strategyId, strategy, family, openedAt, closedAt, side, quantity, entryPrice, exitPrice, grossPaise,
                feesPaise, netPaise, outcomeR, regime, trend, event, news, exitReason, entrySlippageBps, exitSlippageBps, adherencePct, setupValid, hour,
                "UNKNOWN", "UNKNOWN");
    }
}
