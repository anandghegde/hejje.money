package money.hejje.orders.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;
import money.hejje.orders.Trade;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TradeStore {

    private final JdbcClient jdbc;

    TradeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a trade; returns false when this (order, brokerTradeId) was already recorded. */
    public boolean insertIfAbsent(Trade t) {
        int rows = jdbc.sql("""
                INSERT INTO trade (id, order_id, broker_trade_id, instrument_id, side, quantity, price, ts, mode, strategy_id)
                VALUES (:id, :orderId, :brokerTradeId, :instrumentId, :side, :quantity, :price, :ts, :mode, :strategyId)
                ON CONFLICT (order_id, broker_trade_id) DO NOTHING
                """)
                .param("id", t.id()).param("orderId", t.orderId()).param("brokerTradeId", t.brokerTradeId())
                .param("instrumentId", t.instrumentId()).param("side", t.side().name()).param("quantity", t.quantity())
                .param("price", t.price()).param("ts", ts(t.ts())).param("mode", t.mode().name())
                .param("strategyId", t.strategyId(), Types.OTHER)
                .update();
        return rows > 0;
    }

    public List<Trade> query(ExecutionMode mode, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("SELECT * FROM trade WHERE mode = :mode");
        if (from != null) sql.append(" AND ts >= :from");
        if (to != null) sql.append(" AND ts <= :to");
        sql.append(" ORDER BY ts DESC LIMIT 1000");
        var q = jdbc.sql(sql.toString()).param("mode", mode.name());
        if (from != null) q = q.param("from", ts(from));
        if (to != null) q = q.param("to", ts(to));
        return q.query(this::map).list();
    }

    public List<Trade> byOrder(UUID orderId) {
        return jdbc.sql("SELECT * FROM trade WHERE order_id = :id ORDER BY ts").param("id", orderId).query(this::map).list();
    }

    private Trade map(ResultSet rs, int i) throws SQLException {
        return new Trade(
                rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("broker_trade_id"),
                rs.getObject("instrument_id", UUID.class), Side.valueOf(rs.getString("side")), rs.getInt("quantity"),
                rs.getBigDecimal("price"), rs.getObject("ts", OffsetDateTime.class).toInstant(),
                ExecutionMode.valueOf(rs.getString("mode")), rs.getObject("strategy_id", UUID.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
