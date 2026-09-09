package money.hejje.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Side;

/**
 * PRD section 55 postmortem for one closed round trip.
 *
 * @param outcomeR           net P&L over the money risked at entry (null when no stop was known)
 * @param expectedSetupValid entry rules of the (selected) strategy passed at the entry bar; null for pure manual trades
 * @param entrySlippageBps   fill vs the signal's reference price (positive = worse), null when unknown
 * @param exitSlippageBps    fill vs the planned exit level (stop or target), 0 for market exits, null when unknown
 * @param ruleAdherencePct   100 when entry and exit followed the rules; 50 when only the entry did; null for manual
 * @param context            regime / breadth / news / event placeholders until Phase 3
 */
public record TradeReview(UUID id, ExecutionMode mode, UUID positionId, UUID strategyPositionId, UUID strategyId, UUID strategyVersionId, UUID signalId,
        UUID instrumentId, UUID entryOrderId, Side side, int quantity, BigDecimal entryPrice, BigDecimal exitPrice, Instant openedAt, Instant closedAt,
        Money grossPnl, Money fees, Money netPnl, Double outcomeR, Boolean expectedSetupValid, Double entrySlippageBps, Double exitSlippageBps,
        Integer ruleAdherencePct, String closeReason, Map<String, Object> context, String notes, Instant createdAt) {

    public TradeReview {
        // placeholders are null until Phase 3, so keep an insertion-ordered copy that allows null values
        context = context == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(context));
    }
}
