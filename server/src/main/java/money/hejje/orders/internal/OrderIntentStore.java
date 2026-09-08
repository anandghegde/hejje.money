package money.hejje.orders.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.orders.IntentStatus;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderReason;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderIntentStore {

    private final JdbcClient jdbc;
    private final Json json;

    OrderIntentStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.json = new Json(mapper);
    }

    public void insert(OrderIntent intent) {
        jdbc.sql("""
                INSERT INTO order_intent (id, idempotency_key, client_id, source, actor_id, strategy_id, signal_id, instrument_id,
                    side, quantity, order_type, product, limit_price, trigger_price, stop_price, target_price, max_risk_paise,
                    reason, mode, status, validation_errors, created_at)
                VALUES (:id, :key, :clientId, :source, :actorId, :strategyId, :signalId, :instrumentId, :side, :quantity, :orderType,
                    :product, :limit, :trigger, :stop, :target, :maxRisk, :reason, :mode, :status, CAST(:errors AS jsonb), :createdAt)
                """)
                .param("id", intent.id()).param("key", intent.idempotencyKey()).param("clientId", intent.clientId())
                .param("source", intent.source().name()).param("actorId", intent.actorId())
                .param("strategyId", intent.strategyId()).param("signalId", intent.signalId())
                .param("instrumentId", intent.instrumentId()).param("side", intent.side().name())
                .param("quantity", intent.quantity().value()).param("orderType", intent.orderType().name())
                .param("product", intent.product().name())
                .param("limit", price(intent.limitPrice()), Types.NUMERIC).param("trigger", price(intent.triggerPrice()), Types.NUMERIC)
                .param("stop", price(intent.stopPrice()), Types.NUMERIC).param("target", price(intent.targetPrice()), Types.NUMERIC)
                .param("maxRisk", intent.maxRisk() == null ? null : intent.maxRisk().paise(), Types.BIGINT)
                .param("reason", intent.reason().name()).param("mode", intent.mode().name()).param("status", intent.status().name())
                .param("errors", json.write(intent.validationErrors())).param("createdAt", ts(intent.createdAt()))
                .update();
    }

    public void updateStatus(OrderIntent intent) {
        jdbc.sql("UPDATE order_intent SET status = :status, validation_errors = CAST(:errors AS jsonb) WHERE id = :id")
                .param("status", intent.status().name()).param("errors", json.write(intent.validationErrors())).param("id", intent.id())
                .update();
    }

    public Optional<OrderIntent> findById(UUID id) {
        return jdbc.sql("SELECT * FROM order_intent WHERE id = :id").param("id", id).query(this::map).optional();
    }

    private static BigDecimal price(Price p) {
        return p == null ? null : p.value();
    }

    private static OffsetDateTime ts(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private OrderIntent map(ResultSet rs, int i) throws SQLException {
        return new OrderIntent(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getObject("client_id", UUID.class),
                ActorType.valueOf(rs.getString("source")),
                rs.getString("actor_id"),
                rs.getObject("strategy_id", UUID.class),
                rs.getObject("signal_id", UUID.class),
                rs.getObject("instrument_id", UUID.class),
                Side.valueOf(rs.getString("side")),
                Quantity.of(rs.getInt("quantity")),
                OrderType.valueOf(rs.getString("order_type")),
                Product.valueOf(rs.getString("product")),
                priceOf(rs.getBigDecimal("limit_price")),
                priceOf(rs.getBigDecimal("trigger_price")),
                priceOf(rs.getBigDecimal("stop_price")),
                priceOf(rs.getBigDecimal("target_price")),
                rs.getObject("max_risk_paise") == null ? null : Money.ofPaise(rs.getLong("max_risk_paise")),
                OrderReason.valueOf(rs.getString("reason")),
                ExecutionMode.valueOf(rs.getString("mode")),
                IntentStatus.valueOf(rs.getString("status")),
                json.readStrings(rs.getString("validation_errors")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static Price priceOf(BigDecimal v) {
        return v == null ? null : Price.of(v);
    }
}
