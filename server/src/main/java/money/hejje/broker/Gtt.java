package money.hejje.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;

/**
 * Good-till-triggered orders held at the broker (plan M11.2): a trigger condition on the last traded price that places an
 * order when met, alive across sessions (Zerodha: up to a year, 500 per account; docs/swing.md).
 */
public final class Gtt {

    private Gtt() {
    }

    /** {@code SINGLE}: one trigger, one order. {@code OCO}: two triggers (lower, upper), the first met places its order and ends the GTT. */
    public enum Type { SINGLE, OCO }

    /** The broker's GTT states (Kite's vocabulary). */
    public enum Status {
        ACTIVE, TRIGGERED, DISABLED, EXPIRED, CANCELLED, REJECTED, DELETED;

        public boolean isLive() {
            return this == ACTIVE;
        }
    }

    /**
     * The order a trigger places. {@code price} is the limit of a LIMIT leg; a MARKET leg (market price protection at the
     * broker) ignores it.
     */
    public record Leg(Side side, int quantity, OrderType orderType, BigDecimal price, Product product) {}

    /**
     * A GTT to place or the new state of one to modify. {@code triggers} ascend and pair with {@code legs}: SINGLE has one
     * of each, OCO the stop (lower trigger) then the goal (upper trigger). {@code lastPrice} is the price the triggers were
     * set against (the broker decides the trigger direction from it).
     */
    public record Request(UUID instrumentId, Type type, List<BigDecimal> triggers, BigDecimal lastPrice, List<Leg> legs) {

        public Request {
            triggers = List.copyOf(triggers);
            legs = List.copyOf(legs);
            int n = type == Type.SINGLE ? 1 : 2;
            if (triggers.size() != n || legs.size() != n) {
                throw new BrokerException(BrokerException.Kind.INPUT, type + " GTT needs " + n + " trigger(s) and " + n + " leg(s)", false, null);
            }
            if (type == Type.OCO && triggers.get(0).compareTo(triggers.get(1)) >= 0) {
                throw new BrokerException(BrokerException.Kind.INPUT, "OCO triggers must ascend (stop below goal)", false, null);
            }
        }
    }

    /** A GTT as the broker reports it. {@code triggeredOrderId} is the broker order a trigger placed, once triggered. */
    public record Snapshot(String id, UUID instrumentId, String tradingSymbol, Type type, Status status, List<BigDecimal> triggers, List<Leg> legs,
            String triggeredOrderId, Instant createdAt, Instant updatedAt, Map<String, Object> raw) {

        public Snapshot {
            triggers = List.copyOf(triggers);
            legs = List.copyOf(legs);
            raw = raw == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
        }

        public int quantity() {
            return legs.isEmpty() ? 0 : legs.get(0).quantity();
        }
    }
}
