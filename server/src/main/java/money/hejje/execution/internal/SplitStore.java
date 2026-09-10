package money.hejje.execution.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.execution.SplitOrder;
import money.hejje.execution.SplitPolicy;
import money.hejje.orders.OrderReason;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SplitStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    SplitStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(SplitOrder s, String idempotencyKey) {
        try {
            jdbc.sql("""
                    INSERT INTO split_order (id, mode, client_id, idempotency_key, source, actor_id, strategy_id, instrument_id, side, quantity, order_type, product,
                        limit_price, trigger_price, stop_price, target_price, reason, policy, status, filled_quantity, children, reference_price, deadline, detail,
                        created_at, updated_at)
                    VALUES (:id, :mode, :client, :key, :source, :actor, :strategy, :instrument, :side, :qty, :type, :product, :limit, :trigger, :stop, :target, :reason,
                        CAST(:policy AS jsonb), :status, 0, 0, :reference, :deadline, :detail, :createdAt, :updatedAt)
                    """)
                    .param("id", s.id()).param("mode", s.mode().name()).param("client", s.clientId()).param("key", idempotencyKey).param("source", s.source().name())
                    .param("actor", s.actorId()).param("strategy", s.strategyId()).param("instrument", s.instrumentId()).param("side", s.side().name())
                    .param("qty", s.quantity()).param("type", s.orderType().name()).param("product", s.product().name()).param("limit", s.limitPrice())
                    .param("trigger", s.triggerPrice()).param("stop", s.stopPrice()).param("target", s.targetPrice()).param("reason", s.reason().name())
                    .param("policy", json.writeValueAsString(s.policy())).param("status", s.status().name()).param("reference", s.referencePrice())
                    .param("deadline", ts(s.deadline())).param("detail", s.detail()).param("createdAt", ts(s.createdAt())).param("updatedAt", ts(s.updatedAt())).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Optional<SplitOrder> find(UUID id) {
        return jdbc.sql("SELECT * FROM split_order WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<SplitOrder> findByKey(UUID clientId, String key) {
        return jdbc.sql("SELECT * FROM split_order WHERE client_id = :c AND idempotency_key = :k").param("c", clientId).param("k", key).query(this::map).optional();
    }

    public List<SplitOrder> list(ExecutionMode mode, int limit) {
        return jdbc.sql("SELECT * FROM split_order WHERE mode = :mode ORDER BY created_at DESC LIMIT :limit").param("mode", mode.name()).param("limit", limit)
                .query(this::map).list();
    }

    public void childPlaced(UUID id, Instant at) {
        jdbc.sql("UPDATE split_order SET children = children + 1, updated_at = :at WHERE id = :id").param("at", ts(at)).param("id", id).update();
    }

    public void addFilled(UUID id, int quantity, Instant at) {
        jdbc.sql("UPDATE split_order SET filled_quantity = filled_quantity + :q, updated_at = :at WHERE id = :id").param("q", quantity).param("at", ts(at))
                .param("id", id).update();
    }

    /** Ends a WORKING split; false when it had already ended. */
    public boolean finish(UUID id, SplitOrder.Status status, String detail, Instant at) {
        return jdbc.sql("UPDATE split_order SET status = :status, detail = :detail, updated_at = :at WHERE id = :id AND status = 'WORKING'")
                .param("status", status.name()).param("detail", detail).param("at", ts(at)).param("id", id).update() == 1;
    }

    public int failWorking(String detail, Instant at) {
        return jdbc.sql("UPDATE split_order SET status = 'FAILED', detail = :detail, updated_at = :at WHERE status = 'WORKING'").param("detail", detail)
                .param("at", ts(at)).update();
    }

    private SplitOrder map(ResultSet rs, int i) throws SQLException {
        try {
            String strategy = rs.getString("strategy_id");
            return new SplitOrder(rs.getObject("id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")), rs.getObject("client_id", UUID.class),
                    ActorType.valueOf(rs.getString("source")), rs.getString("actor_id"), strategy == null ? null : UUID.fromString(strategy),
                    rs.getObject("instrument_id", UUID.class), Side.valueOf(rs.getString("side")), rs.getInt("quantity"), OrderType.valueOf(rs.getString("order_type")),
                    Product.valueOf(rs.getString("product")), rs.getBigDecimal("limit_price"), rs.getBigDecimal("trigger_price"), rs.getBigDecimal("stop_price"),
                    rs.getBigDecimal("target_price"), OrderReason.valueOf(rs.getString("reason")), json.readValue(rs.getString("policy"), SplitPolicy.class),
                    SplitOrder.Status.valueOf(rs.getString("status")), rs.getInt("filled_quantity"), rs.getInt("children"), rs.getBigDecimal("reference_price"),
                    instant(rs, "deadline"), rs.getString("detail"), instant(rs, "created_at"), instant(rs, "updated_at"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        OffsetDateTime v = rs.getObject(c, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
