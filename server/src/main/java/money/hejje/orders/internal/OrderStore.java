package money.hejje.orders.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderEvent;
import money.hejje.orders.OrderEventSource;
import money.hejje.orders.OrderRole;
import money.hejje.orders.OrderState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderStore {

    private final JdbcClient jdbc;
    private final Json json;

    OrderStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.json = new Json(mapper);
    }

    public void insert(HejjeOrder order) {
        jdbc.sql("""
                INSERT INTO hejje_order (id, intent_id, mode, broker, broker_order_id, tag, instrument_id, side, quantity,
                    filled_quantity, average_price, order_type, product, limit_price, trigger_price, state, last_broker_status,
                    placed_at, updated_at, parent_order_id, role)
                VALUES (:id, :intentId, :mode, :broker, :brokerOrderId, :tag, :instrumentId, :side, :quantity, :filled, :avg,
                    :orderType, :product, :limit, :trigger, :state, :brokerStatus, :placedAt, :updatedAt, :parentId, :role)
                """)
                .param("id", order.id()).param("intentId", order.intentId()).param("mode", order.mode().name())
                .param("broker", order.broker()).param("brokerOrderId", order.brokerOrderId()).param("tag", order.tag())
                .param("instrumentId", order.instrumentId()).param("side", order.side().name()).param("quantity", order.quantity())
                .param("filled", order.filledQuantity()).param("avg", order.averagePrice())
                .param("orderType", order.orderType().name()).param("product", order.product().name())
                .param("limit", order.limitPrice(), Types.NUMERIC).param("trigger", order.triggerPrice(), Types.NUMERIC)
                .param("state", order.state().name()).param("brokerStatus", order.lastBrokerStatus())
                .param("placedAt", ts(order.placedAt()), Types.TIMESTAMP_WITH_TIMEZONE).param("updatedAt", ts(order.updatedAt()))
                .param("parentId", order.parentOrderId(), Types.OTHER).param("role", order.role() == null ? null : order.role().name(), Types.VARCHAR)
                .update();
    }

    public void update(HejjeOrder order) {
        jdbc.sql("""
                UPDATE hejje_order SET broker_order_id = :brokerOrderId, filled_quantity = :filled, average_price = :avg,
                    state = :state, last_broker_status = :brokerStatus, limit_price = :limit, trigger_price = :trigger,
                    quantity = :quantity, placed_at = COALESCE(:placedAt, placed_at), updated_at = :updatedAt WHERE id = :id
                """)
                .param("brokerOrderId", order.brokerOrderId()).param("filled", order.filledQuantity()).param("avg", order.averagePrice())
                .param("state", order.state().name()).param("brokerStatus", order.lastBrokerStatus())
                .param("limit", order.limitPrice(), Types.NUMERIC).param("trigger", order.triggerPrice(), Types.NUMERIC)
                .param("quantity", order.quantity())
                .param("placedAt", ts(order.placedAt()), Types.TIMESTAMP_WITH_TIMEZONE).param("updatedAt", ts(order.updatedAt())).param("id", order.id())
                .update();
    }

    /** Links a child order (split, M5.3) to its parent. */
    public void setParent(UUID orderId, UUID parentId) {
        jdbc.sql("UPDATE hejje_order SET parent_order_id = :p WHERE id = :id").param("p", parentId).param("id", orderId).update();
    }

    public List<HejjeOrder> findByParent(UUID parentId) {
        return jdbc.sql("SELECT * FROM hejje_order WHERE parent_order_id = :p ORDER BY updated_at, id").param("p", parentId).query(this::map).list();
    }

    public Optional<HejjeOrder> findById(UUID id) {
        return jdbc.sql("SELECT * FROM hejje_order WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<HejjeOrder> findByTag(ExecutionMode mode, String tag) {
        return jdbc.sql("SELECT * FROM hejje_order WHERE mode = :mode AND tag = :tag").param("mode", mode.name()).param("tag", tag)
                .query(this::map).optional();
    }

    public Optional<HejjeOrder> findByBrokerOrderId(String broker, String brokerOrderId) {
        return jdbc.sql("SELECT * FROM hejje_order WHERE broker = :broker AND broker_order_id = :id")
                .param("broker", broker).param("id", brokerOrderId).query(this::map).optional();
    }

    public List<HejjeOrder> findByState(ExecutionMode mode, OrderState state) {
        return jdbc.sql("SELECT * FROM hejje_order WHERE mode = :mode AND state = :state ORDER BY updated_at")
                .param("mode", mode.name()).param("state", state.name()).query(this::map).list();
    }

    public List<HejjeOrder> findLive(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM hejje_order WHERE mode = :mode AND state IN ('SUBMITTING','BROKER_ACCEPTED','OPEN','PARTIALLY_FILLED','MODIFY_PENDING','CANCEL_PENDING','UNKNOWN','RECONCILING') ORDER BY updated_at")
                .param("mode", mode.name()).query(this::map).list();
    }

    public List<HejjeOrder> query(ExecutionMode mode, OrderState state, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("SELECT * FROM hejje_order WHERE mode = :mode");
        if (state != null) sql.append(" AND state = :state");
        if (from != null) sql.append(" AND updated_at >= :from");
        if (to != null) sql.append(" AND updated_at <= :to");
        sql.append(" ORDER BY updated_at DESC LIMIT 500");
        var q = jdbc.sql(sql.toString()).param("mode", mode.name());
        if (state != null) q = q.param("state", state.name());
        if (from != null) q = q.param("from", ts(from));
        if (to != null) q = q.param("to", ts(to));
        return q.query(this::map).list();
    }

    // --- events ---------------------------------------------------------------------------------------------------

    public long appendEvent(UUID orderId, OrderState from, OrderState to, OrderEventSource source, Map<String, Object> payload, Instant ts) {
        long seq = jdbc.sql("SELECT COALESCE(max(seq), 0) + 1 FROM order_event WHERE order_id = :id").param("id", orderId)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO order_event (id, order_id, seq, from_state, to_state, source, payload, ts)
                VALUES (:id, :orderId, :seq, :from, :to, :source, CAST(:payload AS jsonb), :ts)
                """)
                .param("id", money.hejje.common.Ids.newId()).param("orderId", orderId).param("seq", seq)
                .param("from", from == null ? null : from.name(), Types.VARCHAR).param("to", to.name()).param("source", source.name())
                .param("payload", json.write(payload == null ? Map.of() : payload)).param("ts", ts(ts))
                .update();
        return seq;
    }

    public List<OrderEvent> events(UUID orderId) {
        return jdbc.sql("SELECT * FROM order_event WHERE order_id = :id ORDER BY seq").param("id", orderId).query(this::mapEvent).list();
    }

    private HejjeOrder map(ResultSet rs, int i) throws SQLException {
        String role = rs.getString("role");
        return new HejjeOrder(
                rs.getObject("id", UUID.class),
                rs.getObject("intent_id", UUID.class),
                ExecutionMode.valueOf(rs.getString("mode")),
                rs.getString("broker"),
                rs.getString("broker_order_id"),
                rs.getString("tag"),
                rs.getObject("instrument_id", UUID.class),
                Side.valueOf(rs.getString("side")),
                rs.getInt("quantity"),
                rs.getInt("filled_quantity"),
                rs.getBigDecimal("average_price"),
                OrderType.valueOf(rs.getString("order_type")),
                Product.valueOf(rs.getString("product")),
                rs.getBigDecimal("limit_price"),
                rs.getBigDecimal("trigger_price"),
                OrderState.valueOf(rs.getString("state")),
                rs.getString("last_broker_status"),
                instant(rs.getObject("placed_at", OffsetDateTime.class)),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("parent_order_id", UUID.class),
                role == null ? null : OrderRole.valueOf(role));
    }

    private OrderEvent mapEvent(ResultSet rs, int i) throws SQLException {
        String from = rs.getString("from_state");
        return new OrderEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getLong("seq"),
                from == null ? null : OrderState.valueOf(from),
                OrderState.valueOf(rs.getString("to_state")),
                OrderEventSource.valueOf(rs.getString("source")),
                json.readMap(rs.getString("payload")),
                rs.getObject("ts", OffsetDateTime.class).toInstant());
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
