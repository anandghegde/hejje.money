package money.hejje.execution.internal;

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
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.execution.Basket;
import money.hejje.execution.BasketLeg;
import money.hejje.orders.OrderReason;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BasketStore {

    private final JdbcClient jdbc;

    BasketStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void insert(Basket b, String idempotencyKey) {
        jdbc.sql("""
                INSERT INTO basket (id, mode, name, client_id, idempotency_key, source, actor_id, strategy_id, reason, policy, rollback, deadline, status,
                    margin_required_paise, margin_available_paise, detail, created_at, updated_at)
                VALUES (:id, :mode, :name, :client, :key, :source, :actor, :strategy, :reason, :policy, :rollback, :deadline, :status, :required, :available,
                    :detail, :createdAt, :updatedAt)
                """)
                .param("id", b.id()).param("mode", b.mode().name()).param("name", b.name()).param("client", b.clientId()).param("key", idempotencyKey)
                .param("source", b.source().name()).param("actor", b.actorId()).param("strategy", b.strategyId()).param("reason", b.reason().name())
                .param("policy", b.policy().name()).param("rollback", b.rollback().name()).param("deadline", ts(b.deadline())).param("status", b.status().name())
                .param("required", b.marginRequired() == null ? null : b.marginRequired().paise())
                .param("available", b.marginAvailable() == null ? null : b.marginAvailable().paise()).param("detail", b.detail())
                .param("createdAt", ts(b.createdAt())).param("updatedAt", ts(b.updatedAt())).update();
        for (BasketLeg l : b.legs()) {
            jdbc.sql("""
                    INSERT INTO basket_leg (id, basket_id, sequence, hedge_first, instrument_id, side, quantity, order_type, product, limit_price, trigger_price,
                        stop_price, target_price, status)
                    VALUES (:id, :basket, :seq, :hedge, :instrument, :side, :qty, :type, :product, :limit, :trigger, :stop, :target, :status)
                    """)
                    .param("id", l.id()).param("basket", b.id()).param("seq", l.sequence()).param("hedge", l.hedgeFirst()).param("instrument", l.instrumentId())
                    .param("side", l.side().name()).param("qty", l.quantity()).param("type", l.orderType().name()).param("product", l.product().name())
                    .param("limit", l.limitPrice()).param("trigger", l.triggerPrice()).param("stop", l.stopPrice()).param("target", l.targetPrice())
                    .param("status", l.status().name()).update();
        }
    }

    public Optional<Basket> find(UUID id) {
        return jdbc.sql("SELECT * FROM basket WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<Basket> findByKey(UUID clientId, String key) {
        return jdbc.sql("SELECT * FROM basket WHERE client_id = :c AND idempotency_key = :k").param("c", clientId).param("k", key).query(this::map).optional();
    }

    public List<Basket> list(ExecutionMode mode, int limit) {
        return jdbc.sql("SELECT * FROM basket WHERE mode = :mode ORDER BY created_at DESC LIMIT :limit").param("mode", mode.name()).param("limit", limit)
                .query(this::map).list();
    }

    public boolean transition(UUID id, Basket.Status from, Basket.Status to, Instant at) {
        return jdbc.sql("UPDATE basket SET status = :to, updated_at = :at WHERE id = :id AND status = :from").param("to", to.name()).param("at", ts(at))
                .param("id", id).param("from", from.name()).update() == 1;
    }

    /** Ends an EXECUTING basket; false when it had already ended. */
    public boolean finish(UUID id, Basket.Status status, String detail, Instant at) {
        return jdbc.sql("UPDATE basket SET status = :status, detail = :detail, updated_at = :at WHERE id = :id AND status = 'EXECUTING'")
                .param("status", status.name()).param("detail", detail).param("at", ts(at)).param("id", id).update() == 1;
    }

    /** Null arguments keep the stored value. */
    public void updateLeg(UUID legId, BasketLeg.Status status, UUID orderId, String detail, Integer executionOrder, UUID rollbackOrderId) {
        jdbc.sql("""
                UPDATE basket_leg SET status = :status, order_id = COALESCE(CAST(:orderId AS uuid), order_id), detail = COALESCE(CAST(:detail AS text), detail),
                    execution_order = COALESCE(CAST(:exec AS int), execution_order), rollback_order_id = COALESCE(CAST(:rollback AS uuid), rollback_order_id)
                WHERE id = :id
                """)
                .param("status", status.name()).param("orderId", orderId).param("detail", detail).param("exec", executionOrder).param("rollback", rollbackOrderId)
                .param("id", legId).update();
    }

    public int failExecuting(String detail, Instant at) {
        return jdbc.sql("UPDATE basket SET status = 'FAILED', detail = :detail, updated_at = :at WHERE status IN ('PENDING', 'EXECUTING')")
                .param("detail", detail).param("at", ts(at)).update();
    }

    private List<BasketLeg> legs(UUID basketId) {
        return jdbc.sql("SELECT * FROM basket_leg WHERE basket_id = :id ORDER BY sequence").param("id", basketId).query((rs, i) -> new BasketLeg(
                rs.getObject("id", UUID.class), rs.getInt("sequence"), rs.getBoolean("hedge_first"), (Integer) rs.getObject("execution_order"),
                rs.getObject("instrument_id", UUID.class), Side.valueOf(rs.getString("side")), rs.getInt("quantity"), OrderType.valueOf(rs.getString("order_type")),
                Product.valueOf(rs.getString("product")), rs.getBigDecimal("limit_price"), rs.getBigDecimal("trigger_price"), rs.getBigDecimal("stop_price"),
                rs.getBigDecimal("target_price"), rs.getObject("order_id", UUID.class), BasketLeg.Status.valueOf(rs.getString("status")), rs.getString("detail"),
                rs.getObject("rollback_order_id", UUID.class))).list();
    }

    private Basket map(ResultSet rs, int i) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Object required = rs.getObject("margin_required_paise");
        Object available = rs.getObject("margin_available_paise");
        return new Basket(id, ExecutionMode.valueOf(rs.getString("mode")), rs.getString("name"), rs.getObject("client_id", UUID.class),
                ActorType.valueOf(rs.getString("source")), rs.getString("actor_id"), rs.getObject("strategy_id", UUID.class), OrderReason.valueOf(rs.getString("reason")),
                Basket.Policy.valueOf(rs.getString("policy")), Basket.Rollback.valueOf(rs.getString("rollback")), instant(rs, "deadline"),
                Basket.Status.valueOf(rs.getString("status")), required == null ? null : Money.ofPaise(((Number) required).longValue()),
                available == null ? null : Money.ofPaise(((Number) available).longValue()), rs.getString("detail"), instant(rs, "created_at"),
                instant(rs, "updated_at"), legs(id));
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        OffsetDateTime v = rs.getObject(c, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
