package money.hejje.analytics.drift.internal;

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
import money.hejje.analytics.drift.DriftReport;
import money.hejje.analytics.drift.DriftService.HistoryItem;
import money.hejje.analytics.drift.DriftState;
import money.hejje.analytics.drift.DriftStatus;
import money.hejje.common.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DriftStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    DriftStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<DriftState> state(UUID deploymentId) {
        return jdbc.sql("SELECT * FROM drift_state WHERE deployment_id = :id").param("id", deploymentId).query(this::map).optional();
    }

    public List<DriftState> statesForVersion(UUID versionId) {
        return jdbc.sql("SELECT * FROM drift_state WHERE version_id = :id ORDER BY updated_at").param("id", versionId).query(this::map).list();
    }

    /** Upserts status, acted status and criteria; the override columns are kept. */
    public void saveState(UUID deploymentId, UUID versionId, UUID strategyId, DriftStatus status, DriftStatus acted, List<String> triggered, Instant at) {
        jdbc.sql("""
                INSERT INTO drift_state (deployment_id, version_id, strategy_id, status, acted_status, triggered, updated_at)
                VALUES (:id, :versionId, :strategyId, :status, :acted, CAST(:triggered AS jsonb), :at)
                ON CONFLICT (deployment_id) DO UPDATE SET status = EXCLUDED.status, acted_status = EXCLUDED.acted_status,
                    triggered = EXCLUDED.triggered, updated_at = EXCLUDED.updated_at
                """)
                .param("id", deploymentId).param("versionId", versionId).param("strategyId", strategyId).param("status", status.name())
                .param("acted", acted.name()).param("triggered", write(triggered)).param("at", ts(at)).update();
    }

    public void setOverride(UUID deploymentId, DriftStatus status, String reason, String by, Instant at) {
        jdbc.sql("UPDATE drift_state SET override_status = :status, override_reason = :reason, override_by = :by, override_at = :at WHERE deployment_id = :id")
                .param("status", status.name()).param("reason", reason).param("by", by).param("at", ts(at)).param("id", deploymentId).update();
    }

    public void clearOverride(UUID deploymentId) {
        jdbc.sql("UPDATE drift_state SET override_status = NULL, override_reason = NULL, override_by = NULL, override_at = NULL WHERE deployment_id = :id")
                .param("id", deploymentId).update();
    }

    public void insertAssessment(DriftReport report, List<String> actions, Instant at) {
        jdbc.sql("""
                INSERT INTO drift_assessment (id, deployment_id, version_id, strategy_id, mode, status, report, actions, created_at)
                VALUES (:id, :deploymentId, :versionId, :strategyId, :mode, :status, CAST(:report AS jsonb), CAST(:actions AS jsonb), :at)
                """)
                .param("id", Ids.newId()).param("deploymentId", report.deploymentId()).param("versionId", report.versionId())
                .param("strategyId", report.strategyId()).param("mode", report.mode()).param("status", report.status().name())
                .param("report", write(report)).param("actions", write(actions)).param("at", ts(at)).update();
    }

    public List<HistoryItem> history(UUID deploymentId, int limit) {
        return jdbc.sql("SELECT id, status, actions, report, created_at FROM drift_assessment WHERE deployment_id = :id ORDER BY created_at DESC LIMIT :limit")
                .param("id", deploymentId).param("limit", limit).query((rs, i) -> {
                    Map<String, Object> report = read(rs.getString("report"), MAP);
                    @SuppressWarnings("unchecked")
                    List<String> triggered = report.get("triggered") instanceof List<?> l ? (List<String>) l : List.of();
                    return new HistoryItem(rs.getObject("id", UUID.class), DriftStatus.valueOf(rs.getString("status")), read(rs.getString("actions"), STRINGS),
                            triggered, instant(rs, "created_at"));
                }).list();
    }

    private DriftState map(ResultSet rs, int i) throws SQLException {
        String override = rs.getString("override_status");
        return new DriftState(rs.getObject("deployment_id", UUID.class), rs.getObject("version_id", UUID.class), rs.getObject("strategy_id", UUID.class),
                DriftStatus.valueOf(rs.getString("status")), DriftStatus.valueOf(rs.getString("acted_status")), read(rs.getString("triggered"), STRINGS),
                override == null ? null : DriftStatus.valueOf(override), rs.getString("override_reason"), rs.getString("override_by"),
                instant(rs, "override_at"), instant(rs, "updated_at"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not serialisable", e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("Bad stored JSON", e);
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
