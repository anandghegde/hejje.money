package money.hejje.options;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Side;

/**
 * A multi-leg options position opened from one signal (plan M5.4): the legs are placed as a basket and then managed
 * here against the per-leg stops/targets, the combined P&L exits, the underlying's stop and the force-exit time.
 *
 * @param direction the signal's side on the underlying
 */
public record OptionsPosition(UUID id, ExecutionMode mode, UUID clientId, ActorType source, String actorId, UUID strategyId, UUID versionId, UUID deploymentId,
        UUID signalId, String underlying, UUID underlyingInstrumentId, Side direction, BigDecimal underlyingStop, UUID basketId, Status status, List<Leg> legs,
        Money combinedStop, Money combinedTarget, LocalTime forceExitTime, Product product, String closeReason, Money realized, String detail, Instant openedAt,
        Instant closedAt, Instant updatedAt) {

    public OptionsPosition {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    public enum Status { PENDING, OPEN, CLOSING, CLOSED, FAILED }

    /** One leg: the order that opened it, and the exit order once closing. */
    public record Leg(int sequence, UUID instrumentId, String symbol, Side side, int quantity, BigDecimal stopPrice, BigDecimal targetPrice, boolean hedgeFirst,
            BigDecimal entryPrice, UUID orderId, UUID exitOrderId, BigDecimal exitPrice, int exitAttempts) {

        public Leg withEntry(BigDecimal price, UUID order) {
            return new Leg(sequence, instrumentId, symbol, side, quantity, stopPrice, targetPrice, hedgeFirst, price, order, exitOrderId, exitPrice, exitAttempts);
        }

        public Leg withExit(UUID order, BigDecimal price, int attempts) {
            return new Leg(sequence, instrumentId, symbol, side, quantity, stopPrice, targetPrice, hedgeFirst, entryPrice, orderId, order, price, attempts);
        }

        /** P&L at {@code price} in rupees (long legs gain as the premium rises, short legs as it falls). */
        public BigDecimal pnlAt(BigDecimal price) {
            BigDecimal diff = price.subtract(entryPrice).multiply(BigDecimal.valueOf(quantity));
            return side == Side.BUY ? diff : diff.negate();
        }
    }

    public OptionsPosition with(Status status, List<Leg> legs, String closeReason, Money realized, String detail, Instant closedAt, Instant at) {
        return new OptionsPosition(id, mode, clientId, source, actorId, strategyId, versionId, deploymentId, signalId, underlying, underlyingInstrumentId, direction,
                underlyingStop, basketId, status, legs, combinedStop, combinedTarget, forceExitTime, product, closeReason, realized, detail, openedAt, closedAt, at);
    }
}
