package money.hejje.execution.internal;

import java.math.BigDecimal;
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
import money.hejje.execution.PositionGtt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code gtt}: the broker-side stops of delivery positions (plan M11.2). */
@Repository
public class GttStore {

    private final JdbcClient jdbc;

    GttStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PositionGtt g) {
        jdbc.sql("""
                INSERT INTO gtt (id, mode, broker, broker_gtt_id, position_id, instrument_id, quantity, stop_trigger, goal_trigger, status,
                    triggered_order_id, created_at, updated_at, confirmed_at)
                VALUES (:id, :mode, :broker, :gttId, :positionId, :instrumentId, :quantity, :stop, :goal, :status, :triggeredOrderId, :createdAt,
                    :updatedAt, :confirmedAt)
                """)
                .param("id", g.id()).param("mode", g.mode().name()).param("broker", g.broker()).param("gttId", g.brokerGttId())
                .param("positionId", g.positionId()).param("instrumentId", g.instrumentId()).param("quantity", g.quantity()).param("stop", g.stop())
                .param("goal", g.goal(), Types.NUMERIC).param("status", g.status().name()).param("triggeredOrderId", g.triggeredOrderId())
                .param("createdAt", ts(g.createdAt())).param("updatedAt", ts(g.updatedAt())).param("confirmedAt", ts(g.confirmedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    public void update(UUID id, String brokerGttId, int quantity, BigDecimal stop, PositionGtt.Status status, String triggeredOrderId, Instant now) {
        jdbc.sql("""
                UPDATE gtt SET broker_gtt_id = :gttId, quantity = :quantity, stop_trigger = :stop, status = :status, triggered_order_id = :orderId,
                    updated_at = :now WHERE id = :id
                """).param("gttId", brokerGttId).param("quantity", quantity).param("stop", stop).param("status", status.name())
                .param("orderId", triggeredOrderId).param("now", ts(now)).param("id", id).update();
    }

    public void confirm(UUID id, Instant now) {
        jdbc.sql("UPDATE gtt SET confirmed_at = :now WHERE id = :id").param("now", ts(now)).param("id", id).update();
    }

    /** The position's ACTIVE or MISSING GTT. */
    public Optional<PositionGtt> live(UUID positionId) {
        return jdbc.sql("SELECT * FROM gtt WHERE position_id = :id AND status IN ('ACTIVE', 'MISSING')").param("id", positionId).query(this::map).optional();
    }

    public List<PositionGtt> live(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM gtt WHERE mode = :mode AND status IN ('ACTIVE', 'MISSING') ORDER BY created_at")
                .param("mode", mode.name()).query(this::map).list();
    }

    public List<PositionGtt> activeByInstrument(ExecutionMode mode, UUID instrumentId) {
        return jdbc.sql("SELECT * FROM gtt WHERE mode = :mode AND instrument_id = :instrument AND status = 'ACTIVE'")
                .param("mode", mode.name()).param("instrument", instrumentId).query(this::map).list();
    }

    /** Whether Hejje ever placed this broker GTT (an orphan Hejje placed, as opposed to one of the account's own). */
    public boolean known(String broker, String brokerGttId) {
        return jdbc.sql("SELECT count(*) FROM gtt WHERE broker = :broker AND broker_gtt_id = :id").param("broker", broker).param("id", brokerGttId)
                .query(Long.class).single() > 0;
    }

    public List<PositionGtt> history(UUID positionId) {
        return jdbc.sql("SELECT * FROM gtt WHERE position_id = :id ORDER BY created_at").param("id", positionId).query(this::map).list();
    }

    private PositionGtt map(ResultSet rs, int i) throws SQLException {
        OffsetDateTime confirmed = rs.getObject("confirmed_at", OffsetDateTime.class);
        return new PositionGtt(rs.getObject("id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")), rs.getString("broker"),
                rs.getString("broker_gtt_id"), rs.getObject("position_id", UUID.class), rs.getObject("instrument_id", UUID.class), rs.getInt("quantity"),
                rs.getBigDecimal("stop_trigger"), rs.getBigDecimal("goal_trigger"), PositionGtt.Status.valueOf(rs.getString("status")),
                rs.getString("triggered_order_id"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), confirmed == null ? null : confirmed.toInstant());
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
