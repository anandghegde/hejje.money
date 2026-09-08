package money.hejje.orders.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.orders.Position;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PositionStore {

    static final UUID MANUAL_STRATEGY = new UUID(0, 0);

    private final JdbcClient jdbc;

    PositionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Position> find(ExecutionMode mode, UUID instrumentId, Product product, UUID strategyId) {
        return jdbc.sql("SELECT * FROM position WHERE mode = :mode AND instrument_id = :instr AND product = :product AND strategy_id = :strategy")
                .param("mode", mode.name()).param("instr", instrumentId).param("product", product.name())
                .param("strategy", strategyId == null ? MANUAL_STRATEGY : strategyId).query(this::map).optional();
    }

    public Optional<Position> findById(UUID id) {
        return jdbc.sql("SELECT * FROM position WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public List<Position> byMode(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM position WHERE mode = :mode ORDER BY updated_at DESC").param("mode", mode.name()).query(this::map).list();
    }

    public List<Position> openByMode(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM position WHERE mode = :mode AND net_quantity <> 0 ORDER BY updated_at DESC").param("mode", mode.name()).query(this::map).list();
    }

    public void upsert(Position p) {
        jdbc.sql("""
                INSERT INTO position (id, mode, instrument_id, product, strategy_id, net_quantity, average_price, realized_pnl_paise,
                    day_buy_qty, day_sell_qty, opened_at, updated_at)
                VALUES (:id, :mode, :instr, :product, :strategy, :net, :avg, :realized, :dayBuy, :daySell, :openedAt, :updatedAt)
                ON CONFLICT (mode, instrument_id, product, strategy_id) DO UPDATE SET net_quantity = EXCLUDED.net_quantity,
                    average_price = EXCLUDED.average_price, realized_pnl_paise = EXCLUDED.realized_pnl_paise,
                    day_buy_qty = EXCLUDED.day_buy_qty, day_sell_qty = EXCLUDED.day_sell_qty, updated_at = EXCLUDED.updated_at
                """)
                .param("id", p.id()).param("mode", p.mode().name()).param("instr", p.instrumentId()).param("product", p.product().name())
                .param("strategy", p.strategyId() == null ? MANUAL_STRATEGY : p.strategyId()).param("net", p.netQuantity())
                .param("avg", p.averagePrice()).param("realized", p.realizedPnl().paise()).param("dayBuy", p.dayBuyQty())
                .param("daySell", p.daySellQty()).param("openedAt", ts(p.openedAt())).param("updatedAt", ts(p.updatedAt()))
                .update();
    }

    private Position map(ResultSet rs, int i) throws SQLException {
        UUID strategy = rs.getObject("strategy_id", UUID.class);
        return new Position(
                rs.getObject("id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")), rs.getObject("instrument_id", UUID.class),
                Product.valueOf(rs.getString("product")), MANUAL_STRATEGY.equals(strategy) ? null : strategy,
                rs.getInt("net_quantity"), rs.getBigDecimal("average_price"), Money.ofPaise(rs.getLong("realized_pnl_paise")),
                rs.getInt("day_buy_qty"), rs.getInt("day_sell_qty"),
                rs.getObject("opened_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
