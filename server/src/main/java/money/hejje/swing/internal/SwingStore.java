package money.hejje.swing.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.swing.SwingPosition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code swing_position}: the swing book's round trips (plan M11.1). */
@Repository
public class SwingStore {

    private final JdbcClient jdbc;

    SwingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts an OPEN row unless the position already has one (the listener may see two fills of one entry at once). */
    public void insertOpen(SwingPosition p, Instant now) {
        jdbc.sql("""
                INSERT INTO swing_position (id, mode, position_id, instrument_id, strategy_id, entry_order_id, opened_at, entry_date, quantity, entry_price,
                    initial_stop, goal, status, updated_at, trail)
                VALUES (:id, :mode, :positionId, :instrumentId, :strategyId, :entryOrderId, :openedAt, :entryDate, :quantity, :entryPrice, :initialStop, :goal,
                    'OPEN', :now, :trail)
                ON CONFLICT (position_id) WHERE status = 'OPEN' DO NOTHING
                """)
                .param("id", p.id()).param("mode", p.mode().name()).param("positionId", p.positionId()).param("instrumentId", p.instrumentId())
                .param("strategyId", p.strategyId(), Types.OTHER).param("entryOrderId", p.entryOrderId()).param("openedAt", ts(p.openedAt()))
                .param("entryDate", p.entryDate()).param("quantity", p.quantity()).param("entryPrice", p.entryPrice())
                .param("initialStop", p.initialStop(), Types.NUMERIC).param("goal", p.goal(), Types.NUMERIC).param("now", ts(now)).param("trail", p.trail()).update();
    }

    public void updateOpen(UUID id, int quantity, java.math.BigDecimal entryPrice, Instant now) {
        jdbc.sql("UPDATE swing_position SET quantity = :q, entry_price = :p, updated_at = :now WHERE id = :id AND status = 'OPEN'")
                .param("q", quantity).param("p", entryPrice).param("now", ts(now)).param("id", id).update();
    }

    public void close(UUID id, Instant closedAt, java.time.LocalDate exitDate, java.math.BigDecimal exitPrice, int holdingDays) {
        jdbc.sql("""
                UPDATE swing_position SET status = 'CLOSED', closed_at = :closedAt, exit_date = :exitDate, exit_price = :exitPrice, holding_days = :days,
                    updated_at = :closedAt
                WHERE id = :id AND status = 'OPEN'
                """).param("closedAt", ts(closedAt)).param("exitDate", exitDate).param("exitPrice", exitPrice, Types.NUMERIC)
                .param("days", holdingDays).param("id", id).update();
    }

    public Optional<SwingPosition> findOpen(UUID positionId) {
        return jdbc.sql("SELECT * FROM swing_position WHERE position_id = :id AND status = 'OPEN'").param("id", positionId).query(this::map).optional();
    }

    public Optional<SwingPosition> find(UUID id) {
        return jdbc.sql("SELECT * FROM swing_position WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public List<SwingPosition> list(ExecutionMode mode, SwingPosition.Status status, int limit) {
        return jdbc.sql("SELECT * FROM swing_position WHERE mode = :mode AND status = :status ORDER BY opened_at DESC LIMIT :limit")
                .param("mode", mode.name()).param("status", status.name()).param("limit", limit).query(this::map).list();
    }

    private SwingPosition map(ResultSet rs, int i) throws SQLException {
        OffsetDateTime closed = rs.getObject("closed_at", OffsetDateTime.class);
        Integer days = rs.getObject("holding_days", Integer.class);
        return new SwingPosition(rs.getObject("id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")), rs.getObject("position_id", UUID.class),
                rs.getObject("instrument_id", UUID.class), rs.getObject("strategy_id", UUID.class), rs.getObject("entry_order_id", UUID.class),
                rs.getObject("opened_at", OffsetDateTime.class).toInstant(), rs.getObject("entry_date", java.time.LocalDate.class), rs.getInt("quantity"),
                rs.getBigDecimal("entry_price"), rs.getBigDecimal("initial_stop"), rs.getBigDecimal("goal"), SwingPosition.Status.valueOf(rs.getString("status")),
                closed == null ? null : closed.toInstant(), rs.getObject("exit_date", java.time.LocalDate.class), rs.getBigDecimal("exit_price"),
                days, rs.getBoolean("trail"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
