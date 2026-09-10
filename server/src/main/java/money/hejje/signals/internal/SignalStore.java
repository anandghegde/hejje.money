package money.hejje.signals.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Side;
import money.hejje.signals.CloseReason;
import money.hejje.signals.PositionStatus;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalStatus;
import money.hejje.signals.StrategyPosition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SignalStore {

    private static final TypeReference<List<Map<String, Object>>> EVIDENCE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    SignalStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // --- signals ---

    public void insert(Signal s) {
        jdbc.sql("""
                INSERT INTO signal (id, version_id, strategy_id, deployment_id, instrument_id, mode, side, reference_price, stop, target, risk_per_unit,
                    bar_time, valid_until, evidence, status, note, intent_id, order_id, created_at, updated_at)
                VALUES (:id, :versionId, :strategyId, :deploymentId, :instrumentId, :mode, :side, :ref, :stop, :target, :risk, :barTime, :validUntil,
                    CAST(:evidence AS jsonb), :status, :note, :intentId, :orderId, :createdAt, :updatedAt)
                """)
                .param("id", s.id()).param("versionId", s.versionId()).param("strategyId", s.strategyId()).param("deploymentId", s.deploymentId())
                .param("instrumentId", s.instrumentId()).param("mode", s.mode().name()).param("side", s.side().name()).param("ref", s.referencePrice())
                .param("stop", s.stop()).param("target", s.target()).param("risk", s.riskPerUnit()).param("barTime", ts(s.barTime()))
                .param("validUntil", ts(s.validUntil())).param("evidence", write(s.evidence())).param("status", s.status().name()).param("note", s.note())
                .param("intentId", s.intentId()).param("orderId", s.orderId()).param("createdAt", ts(s.createdAt())).param("updatedAt", ts(s.updatedAt())).update();
    }

    public void update(Signal s) {
        jdbc.sql("UPDATE signal SET status = :status, note = :note, intent_id = :intentId, order_id = :orderId, updated_at = :updatedAt WHERE id = :id")
                .param("status", s.status().name()).param("note", s.note()).param("intentId", s.intentId()).param("orderId", s.orderId())
                .param("updatedAt", ts(s.updatedAt())).param("id", s.id()).update();
    }

    public Optional<Signal> find(UUID id) {
        return jdbc.sql("SELECT * FROM signal WHERE id = :id").param("id", id).query(this::mapSignal).optional();
    }

    public List<Signal> query(ExecutionMode mode, SignalStatus status, Instant from, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM signal WHERE mode = :mode");
        var spec = jdbc.sql("");
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("mode", mode.name());
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        if (from != null) {
            sql.append(" AND created_at >= :from");
            params.put("from", ts(from));
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(this::mapSignal).list();
    }

    public List<Signal> actionable(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM signal WHERE mode = :mode AND status IN ('ACTIVE', 'PREPARED') ORDER BY created_at")
                .param("mode", mode.name()).query(this::mapSignal).list();
    }

    // --- positions ---

    public void insert(StrategyPosition p) {
        jdbc.sql("""
                INSERT INTO strategy_position (id, signal_id, deployment_id, version_id, strategy_id, instrument_id, mode, side, quantity, entry_price,
                    initial_stop, stop, target, entry_order_id, stop_order_id, exit_order_id, status, close_reason, exit_price, opened_at, closed_at, updated_at)
                VALUES (:id, :signalId, :deploymentId, :versionId, :strategyId, :instrumentId, :mode, :side, :qty, :entry, :initialStop, :stop, :target,
                    :entryOrderId, :stopOrderId, :exitOrderId, :status, :closeReason, :exitPrice, :openedAt, :closedAt, :updatedAt)
                """)
                .params(params(p)).update();
    }

    public void update(StrategyPosition p) {
        jdbc.sql("""
                UPDATE strategy_position SET quantity = :qty, entry_price = :entry, stop = :stop, target = :target, entry_order_id = :entryOrderId,
                    stop_order_id = :stopOrderId, exit_order_id = :exitOrderId, status = :status, close_reason = :closeReason, exit_price = :exitPrice,
                    opened_at = :openedAt, closed_at = :closedAt, updated_at = :updatedAt
                WHERE id = :id
                """)
                .params(params(p)).update();
    }

    private Map<String, Object> params(StrategyPosition p) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", p.id());
        m.put("signalId", p.signalId());
        m.put("deploymentId", p.deploymentId());
        m.put("versionId", p.versionId());
        m.put("strategyId", p.strategyId());
        m.put("instrumentId", p.instrumentId());
        m.put("mode", p.mode().name());
        m.put("side", p.side().name());
        m.put("qty", p.quantity());
        m.put("entry", p.entryPrice());
        m.put("initialStop", p.initialStop());
        m.put("stop", p.stop());
        m.put("target", p.target());
        m.put("entryOrderId", p.entryOrderId());
        m.put("stopOrderId", p.stopOrderId());
        m.put("exitOrderId", p.exitOrderId());
        m.put("status", p.status().name());
        m.put("closeReason", p.closeReason() == null ? null : p.closeReason().name());
        m.put("exitPrice", p.exitPrice());
        m.put("openedAt", ts(p.openedAt()));
        m.put("closedAt", p.closedAt() == null ? null : ts(p.closedAt()));
        m.put("updatedAt", ts(p.updatedAt()));
        return m;
    }

    /** Patches only the entry order id (the fill listener may already have moved the row on). */
    public void setEntryOrder(UUID positionId, UUID entryOrderId) {
        jdbc.sql("UPDATE strategy_position SET entry_order_id = :o WHERE id = :id").param("o", entryOrderId).param("id", positionId).update();
    }

    public Optional<StrategyPosition> findPosition(UUID id) {
        return jdbc.sql("SELECT * FROM strategy_position WHERE id = :id").param("id", id).query(this::mapPosition).optional();
    }

    public List<StrategyPosition> livePositions(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM strategy_position WHERE mode = :mode AND status <> 'CLOSED' ORDER BY opened_at")
                .param("mode", mode.name()).query(this::mapPosition).list();
    }

    public List<StrategyPosition> positions(ExecutionMode mode, boolean liveOnly, int limit) {
        return jdbc.sql("SELECT * FROM strategy_position WHERE mode = :mode" + (liveOnly ? " AND status <> 'CLOSED'" : "") + " ORDER BY opened_at DESC LIMIT :limit")
                .param("mode", mode.name()).param("limit", limit).query(this::mapPosition).list();
    }

    public Optional<StrategyPosition> findBySignal(UUID signalId) {
        return jdbc.sql("SELECT * FROM strategy_position WHERE signal_id = :id ORDER BY updated_at DESC LIMIT 1")
                .param("id", signalId).query(this::mapPosition).optional();
    }

    public Optional<StrategyPosition> findByOrder(UUID orderId) {
        return jdbc.sql("SELECT * FROM strategy_position WHERE entry_order_id = :id OR stop_order_id = :id OR exit_order_id = :id ORDER BY updated_at DESC LIMIT 1")
                .param("id", orderId).query(this::mapPosition).optional();
    }

    /** Positions opened today (IST) per deployment × instrument, for the runner's trades-per-day counter after a restart. */
    public int openedSince(UUID deploymentId, UUID instrumentId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM strategy_position WHERE deployment_id = :d AND instrument_id = :i AND opened_at >= :since AND status <> 'CLOSED' "
                + "OR (deployment_id = :d AND instrument_id = :i AND opened_at >= :since AND close_reason <> 'ENTRY_FAILED')")
                .param("d", deploymentId).param("i", instrumentId).param("since", ts(since)).query(Integer.class).single();
    }

    /** Closed paper trades of a version (an entry filled and the position closed), for AUTO qualification (plan M5.2). */
    public int closedPaperTrades(UUID versionId) {
        return jdbc.sql("SELECT count(*) FROM strategy_position WHERE version_id = :v AND mode = 'PAPER' AND status = 'CLOSED' AND entry_price IS NOT NULL "
                + "AND close_reason <> 'ENTRY_FAILED'").param("v", versionId).query(Integer.class).single();
    }

    /** Entries of a deployment since {@code since} on any instrument (failed entries do not count). */
    public int entriesSince(UUID deploymentId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM strategy_position WHERE deployment_id = :d AND opened_at >= :since AND (status <> 'CLOSED' OR close_reason <> 'ENTRY_FAILED')")
                .param("d", deploymentId).param("since", ts(since)).query(Integer.class).single();
    }

    /** Gross realized P&L (rupees) of a deployment's positions closed since {@code since}. */
    public java.math.BigDecimal realizedSince(UUID deploymentId, Instant since) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN side = 'BUY' THEN exit_price - entry_price ELSE entry_price - exit_price END * quantity), 0)
                FROM strategy_position WHERE deployment_id = :d AND status = 'CLOSED' AND closed_at >= :since AND entry_price IS NOT NULL AND exit_price IS NOT NULL
                """).param("d", deploymentId).param("since", ts(since)).query(java.math.BigDecimal.class).single();
    }

    private Signal mapSignal(ResultSet rs, int i) throws SQLException {
        return new Signal(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class), rs.getObject("strategy_id", UUID.class),
                uuid(rs, "deployment_id"), rs.getObject("instrument_id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")), Side.valueOf(rs.getString("side")),
                rs.getBigDecimal("reference_price"), rs.getBigDecimal("stop"), rs.getBigDecimal("target"), rs.getBigDecimal("risk_per_unit"),
                instant(rs, "bar_time"), instant(rs, "valid_until"), read(rs.getString("evidence")), SignalStatus.valueOf(rs.getString("status")),
                rs.getString("note"), uuid(rs, "intent_id"), uuid(rs, "order_id"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private StrategyPosition mapPosition(ResultSet rs, int i) throws SQLException {
        String reason = rs.getString("close_reason");
        return new StrategyPosition(rs.getObject("id", UUID.class), rs.getObject("signal_id", UUID.class), uuid(rs, "deployment_id"),
                rs.getObject("version_id", UUID.class), rs.getObject("strategy_id", UUID.class), rs.getObject("instrument_id", UUID.class),
                ExecutionMode.valueOf(rs.getString("mode")), Side.valueOf(rs.getString("side")), rs.getInt("quantity"), rs.getBigDecimal("entry_price"),
                rs.getBigDecimal("initial_stop"), rs.getBigDecimal("stop"), rs.getBigDecimal("target"), uuid(rs, "entry_order_id"), uuid(rs, "stop_order_id"),
                uuid(rs, "exit_order_id"), PositionStatus.valueOf(rs.getString("status")), reason == null ? null : CloseReason.valueOf(reason),
                rs.getBigDecimal("exit_price"), instant(rs, "opened_at"), instant(rs, "closed_at"), instant(rs, "updated_at"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return v == null ? null : (UUID) v;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not serialisable", e);
        }
    }

    private List<Map<String, Object>> read(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, EVIDENCE);
        } catch (Exception e) {
            throw new IllegalStateException("Bad stored evidence", e);
        }
    }
}
