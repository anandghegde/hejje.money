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

    /** Every trade column plus its order's product (plan M11.1: the swing book is the CNC fills). */
    private static final String COLUMNS = "trade.*, (SELECT o.product FROM hejje_order o WHERE o.id = trade.order_id) AS product";

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
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM trade WHERE mode = :mode");
        if (from != null) sql.append(" AND ts >= :from");
        if (to != null) sql.append(" AND ts <= :to");
        sql.append(" ORDER BY ts DESC LIMIT 1000");
        var q = jdbc.sql(sql.toString()).param("mode", mode.name());
        if (from != null) q = q.param("from", ts(from));
        if (to != null) q = q.param("to", ts(to));
        return q.query(this::map).list();
    }

    public List<Trade> queryInstrument(ExecutionMode mode, UUID instrumentId, Instant from, Instant to) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trade WHERE mode = :mode AND instrument_id = :instrument AND ts >= :from AND ts <= :to ORDER BY ts")
                .param("mode", mode.name()).param("instrument", instrumentId).param("from", ts(from)).param("to", ts(to)).query(this::map).list();
    }

    /** True when a delivery (CNC) sell of the same scrip and mode was filled earlier on the trade's IST day (plan M11.1). */
    public boolean hasEarlierDeliverySell(Trade t, java.time.ZoneId zone) {
        Instant dayStart = t.ts().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        return jdbc.sql("""
                SELECT count(*) FROM trade t JOIN hejje_order o ON o.id = t.order_id
                WHERE t.mode = :mode AND t.instrument_id = :instrument AND t.side = 'SELL' AND o.product = 'CNC'
                  AND t.ts >= :dayStart AND (t.ts < :ts OR (t.ts = :ts AND t.id < :id))
                """)
                .param("mode", t.mode().name()).param("instrument", t.instrumentId()).param("dayStart", ts(dayStart))
                .param("ts", ts(t.ts())).param("id", t.id()).query(Long.class).single() > 0;
    }

    public List<Trade> byOrder(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trade WHERE order_id = :id ORDER BY ts").param("id", orderId).query(this::map).list();
    }

    private Trade map(ResultSet rs, int i) throws SQLException {
        return new Trade(
                rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("broker_trade_id"),
                rs.getObject("instrument_id", UUID.class), Side.valueOf(rs.getString("side")), rs.getInt("quantity"),
                rs.getBigDecimal("price"), rs.getObject("ts", OffsetDateTime.class).toInstant(),
                ExecutionMode.valueOf(rs.getString("mode")), rs.getObject("strategy_id", UUID.class),
                rs.getString("product") == null ? null : money.hejje.common.Product.valueOf(rs.getString("product")));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
