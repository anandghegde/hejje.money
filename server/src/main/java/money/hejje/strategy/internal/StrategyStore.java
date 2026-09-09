package money.hejje.strategy.internal;

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
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyFamily;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain JDBC access to {@code strategy}, {@code strategy_version} and {@code strategy_deployment}. */
@Repository
public class StrategyStore {

    private static final TypeReference<List<UUID>> UUIDS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private static final String STRATEGY_SELECT = """
            SELECT s.*, v.version AS latest_version, v.id AS latest_version_id, v.status AS latest_status
            FROM strategy s
            LEFT JOIN LATERAL (SELECT id, version, status FROM strategy_version WHERE strategy_id = s.id
                               ORDER BY version DESC LIMIT 1) v ON TRUE
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final DefinitionParser parser;

    StrategyStore(JdbcClient jdbc, ObjectMapper json, DefinitionParser parser) {
        this.jdbc = jdbc;
        this.json = json;
        this.parser = parser;
    }

    // --- strategy ---

    public void insertStrategy(UUID id, String slug, StrategyFamily family, String name, Instant createdAt) {
        jdbc.sql("INSERT INTO strategy (id, slug, family, name, created_at) VALUES (:id, :slug, :family, :name, :createdAt)")
                .param("id", id).param("slug", slug).param("family", family.name()).param("name", name)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC)).update();
    }

    public void retireStrategy(UUID id, Instant at) {
        jdbc.sql("UPDATE strategy SET retired_at = :at WHERE id = :id AND retired_at IS NULL")
                .param("at", at.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }

    public List<Strategy> findAllStrategies() {
        return jdbc.sql(STRATEGY_SELECT + " ORDER BY s.slug").query(this::mapStrategy).list();
    }

    public Optional<Strategy> findStrategy(UUID id) {
        return jdbc.sql(STRATEGY_SELECT + " WHERE s.id = :id").param("id", id).query(this::mapStrategy).optional();
    }

    public Optional<Strategy> findStrategyBySlug(String slug) {
        return jdbc.sql(STRATEGY_SELECT + " WHERE s.slug = :slug").param("slug", slug).query(this::mapStrategy).optional();
    }

    private Strategy mapStrategy(ResultSet rs, int i) throws SQLException {
        String latestStatus = rs.getString("latest_status");
        Object latestId = rs.getObject("latest_version_id");
        return new Strategy(rs.getObject("id", UUID.class), rs.getString("slug"), StrategyFamily.valueOf(rs.getString("family")),
                rs.getString("name"), instant(rs, "created_at"), instant(rs, "retired_at"), rs.getInt("latest_version"),
                latestId == null ? null : (UUID) latestId, latestStatus == null ? null : VersionStatus.valueOf(latestStatus));
    }

    // --- versions ---

    public void insertVersion(StrategyVersion v) {
        jdbc.sql("""
                INSERT INTO strategy_version (id, strategy_id, version, definition_yaml, definition_hash, change_note,
                    parent_version_id, created_by, created_at, status)
                VALUES (:id, :strategyId, :version, :yaml, :hash, :note, :parent, :createdBy, :createdAt, :status)
                """)
                .param("id", v.id()).param("strategyId", v.strategyId()).param("version", v.version())
                .param("yaml", v.definitionYaml()).param("hash", v.definitionHash()).param("note", v.changeNote())
                .param("parent", v.parentVersionId()).param("createdBy", v.createdBy())
                .param("createdAt", v.createdAt().atOffset(ZoneOffset.UTC)).param("status", v.status().name()).update();
    }

    public void updateVersionStatus(UUID versionId, VersionStatus status) {
        jdbc.sql("UPDATE strategy_version SET status = :status WHERE id = :id").param("status", status.name()).param("id", versionId).update();
    }

    public List<StrategyVersion> findVersions(UUID strategyId) {
        return jdbc.sql("SELECT * FROM strategy_version WHERE strategy_id = :sid ORDER BY version")
                .param("sid", strategyId).query(this::mapVersion).list();
    }

    public Optional<StrategyVersion> findVersion(UUID strategyId, int version) {
        return jdbc.sql("SELECT * FROM strategy_version WHERE strategy_id = :sid AND version = :v")
                .param("sid", strategyId).param("v", version).query(this::mapVersion).optional();
    }

    public Optional<StrategyVersion> findVersionById(UUID id) {
        return jdbc.sql("SELECT * FROM strategy_version WHERE id = :id").param("id", id).query(this::mapVersion).optional();
    }

    public Optional<StrategyVersion> findLatestVersion(UUID strategyId) {
        return jdbc.sql("SELECT * FROM strategy_version WHERE strategy_id = :sid ORDER BY version DESC LIMIT 1")
                .param("sid", strategyId).query(this::mapVersion).optional();
    }

    public Optional<StrategyVersion> findVersionByHash(UUID strategyId, String hash) {
        return jdbc.sql("SELECT * FROM strategy_version WHERE strategy_id = :sid AND definition_hash = :hash ORDER BY version DESC LIMIT 1")
                .param("sid", strategyId).param("hash", hash).query(this::mapVersion).optional();
    }

    public List<StrategyVersion> findVersionsByStatus(List<VersionStatus> statuses) {
        return jdbc.sql("SELECT * FROM strategy_version WHERE status IN (:statuses) ORDER BY strategy_id, version")
                .param("statuses", statuses.stream().map(Enum::name).toList()).query(this::mapVersion).list();
    }

    private StrategyVersion mapVersion(ResultSet rs, int i) throws SQLException {
        String yaml = rs.getString("definition_yaml");
        StrategyDefinition definition = parser.parse(yaml);
        Object parent = rs.getObject("parent_version_id");
        return new StrategyVersion(rs.getObject("id", UUID.class), rs.getObject("strategy_id", UUID.class), rs.getInt("version"),
                yaml, definition, rs.getString("definition_hash"), rs.getString("change_note"), parent == null ? null : (UUID) parent,
                rs.getString("created_by"), instant(rs, "created_at"), VersionStatus.valueOf(rs.getString("status")));
    }

    // --- deployments ---

    public void insertDeployment(StrategyDeployment d) {
        jdbc.sql("""
                INSERT INTO strategy_deployment (id, version_id, mode, instrument_ids, autonomy_level, enabled, params, created_at, paused_at, pause_reason)
                VALUES (:id, :versionId, :mode, CAST(:instruments AS jsonb), :autonomy, :enabled, CAST(:params AS jsonb), :createdAt, :pausedAt, :pauseReason)
                """)
                .param("id", d.id()).param("versionId", d.versionId()).param("mode", d.mode().name())
                .param("instruments", write(d.instrumentIds())).param("autonomy", d.autonomyLevel()).param("enabled", d.enabled())
                .param("params", write(d.params())).param("createdAt", d.createdAt().atOffset(ZoneOffset.UTC))
                .param("pausedAt", d.pausedAt() == null ? null : d.pausedAt().atOffset(ZoneOffset.UTC))
                .param("pauseReason", d.pauseReason()).update();
    }

    public void updateDeployment(UUID id, boolean enabled, Instant pausedAt, String pauseReason) {
        jdbc.sql("UPDATE strategy_deployment SET enabled = :enabled, paused_at = :pausedAt, pause_reason = :reason WHERE id = :id")
                .param("enabled", enabled).param("pausedAt", pausedAt == null ? null : pausedAt.atOffset(ZoneOffset.UTC))
                .param("reason", pauseReason).param("id", id).update();
    }

    public Optional<StrategyDeployment> findDeployment(UUID id) {
        return jdbc.sql("SELECT d.*, v.strategy_id FROM strategy_deployment d JOIN strategy_version v ON v.id = d.version_id WHERE d.id = :id")
                .param("id", id).query(this::mapDeployment).optional();
    }

    public List<StrategyDeployment> findDeployments(UUID versionId, ExecutionMode mode, Boolean enabled) {
        StringBuilder sql = new StringBuilder("SELECT d.*, v.strategy_id FROM strategy_deployment d JOIN strategy_version v ON v.id = d.version_id WHERE TRUE");
        Map<String, Object> params = new java.util.HashMap<>();
        if (versionId != null) {
            sql.append(" AND d.version_id = :versionId");
            params.put("versionId", versionId);
        }
        if (mode != null) {
            sql.append(" AND d.mode = :mode");
            params.put("mode", mode.name());
        }
        if (enabled != null) {
            sql.append(" AND d.enabled = :enabled");
            params.put("enabled", enabled);
        }
        sql.append(" ORDER BY d.created_at");
        return jdbc.sql(sql.toString()).params(params).query(this::mapDeployment).list();
    }

    private StrategyDeployment mapDeployment(ResultSet rs, int i) throws SQLException {
        return new StrategyDeployment(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getObject("strategy_id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")),
                read(rs.getString("instrument_ids"), UUIDS), rs.getInt("autonomy_level"), rs.getBoolean("enabled"),
                read(rs.getString("params"), MAP), instant(rs, "created_at"), instant(rs, "paused_at"), rs.getString("pause_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not serialisable: " + value, e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("Bad stored JSON", e);
        }
    }
}
