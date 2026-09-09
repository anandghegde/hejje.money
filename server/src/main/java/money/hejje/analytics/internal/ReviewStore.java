package money.hejje.analytics.internal;

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
import money.hejje.analytics.TradeReview;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Side;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ReviewStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(TradeReview r) {
        try {
            jdbc.sql("""
                    INSERT INTO trade_review (id, mode, position_id, strategy_position_id, strategy_id, strategy_version_id, signal_id, instrument_id, entry_order_id,
                        side, quantity, entry_price, exit_price, opened_at, closed_at, gross_paise, fees_paise, net_paise, outcome_r, expected_setup_valid,
                        entry_slippage_bps, exit_slippage_bps, rule_adherence_pct, close_reason, context, notes, created_at)
                    VALUES (:id, :mode, :positionId, :spId, :strategyId, :versionId, :signalId, :instrumentId, :entryOrderId, :side, :qty, :entry, :exit, :openedAt,
                        :closedAt, :gross, :fees, :net, :r, :valid, :entrySlip, :exitSlip, :adherence, :closeReason, CAST(:context AS jsonb), :notes, :createdAt)
                    ON CONFLICT (entry_order_id) DO NOTHING
                    """)
                    .param("id", r.id()).param("mode", r.mode().name()).param("positionId", r.positionId()).param("spId", r.strategyPositionId())
                    .param("strategyId", r.strategyId()).param("versionId", r.strategyVersionId()).param("signalId", r.signalId()).param("instrumentId", r.instrumentId())
                    .param("entryOrderId", r.entryOrderId()).param("side", r.side().name()).param("qty", r.quantity()).param("entry", r.entryPrice())
                    .param("exit", r.exitPrice()).param("openedAt", ts(r.openedAt())).param("closedAt", ts(r.closedAt())).param("gross", r.grossPnl().paise())
                    .param("fees", r.fees().paise()).param("net", r.netPnl().paise()).param("r", r.outcomeR()).param("valid", r.expectedSetupValid())
                    .param("entrySlip", r.entrySlippageBps()).param("exitSlip", r.exitSlippageBps()).param("adherence", r.ruleAdherencePct())
                    .param("closeReason", r.closeReason()).param("context", json.writeValueAsString(r.context())).param("notes", r.notes())
                    .param("createdAt", ts(r.createdAt())).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Optional<TradeReview> find(UUID id) {
        return jdbc.sql("SELECT * FROM trade_review WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<TradeReview> findByEntryOrder(UUID entryOrderId) {
        return jdbc.sql("SELECT * FROM trade_review WHERE entry_order_id = :id").param("id", entryOrderId).query(this::map).optional();
    }

    public List<TradeReview> list(ExecutionMode mode, int limit) {
        return jdbc.sql("SELECT * FROM trade_review WHERE mode = :mode ORDER BY closed_at DESC LIMIT :limit").param("mode", mode.name()).param("limit", limit)
                .query(this::map).list();
    }

    private TradeReview map(ResultSet rs, int i) throws SQLException {
        try {
            return new TradeReview(rs.getObject("id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")), uuid(rs, "position_id"), uuid(rs, "strategy_position_id"),
                    uuid(rs, "strategy_id"), uuid(rs, "strategy_version_id"), uuid(rs, "signal_id"), rs.getObject("instrument_id", UUID.class),
                    rs.getObject("entry_order_id", UUID.class), Side.valueOf(rs.getString("side")), rs.getInt("quantity"), rs.getBigDecimal("entry_price"),
                    rs.getBigDecimal("exit_price"), instant(rs, "opened_at"), instant(rs, "closed_at"), Money.ofPaise(rs.getLong("gross_paise")),
                    Money.ofPaise(rs.getLong("fees_paise")), Money.ofPaise(rs.getLong("net_paise")), dbl(rs, "outcome_r"), bool(rs, "expected_setup_valid"),
                    dbl(rs, "entry_slippage_bps"), dbl(rs, "exit_slippage_bps"), integer(rs, "rule_adherence_pct"), rs.getString("close_reason"),
                    json.readValue(rs.getString("context"), MAP), rs.getString("notes"), instant(rs, "created_at"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID uuid(ResultSet rs, String c) throws SQLException {
        Object v = rs.getObject(c);
        return v == null ? null : (UUID) v;
    }

    private static Double dbl(ResultSet rs, String c) throws SQLException {
        Object v = rs.getObject(c);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Integer integer(ResultSet rs, String c) throws SQLException {
        Object v = rs.getObject(c);
        return v == null ? null : ((Number) v).intValue();
    }

    private static Boolean bool(ResultSet rs, String c) throws SQLException {
        Object v = rs.getObject(c);
        return v == null ? null : (Boolean) v;
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        OffsetDateTime v = rs.getObject(c, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
