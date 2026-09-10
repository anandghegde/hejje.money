package money.hejje.backtest.experiments.internal;

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
import money.hejje.backtest.Splits;
import money.hejje.backtest.experiments.Dataset;
import money.hejje.backtest.experiments.Experiment;
import money.hejje.backtest.experiments.ExperimentStatus;
import money.hejje.backtest.experiments.Variant;
import money.hejje.backtest.experiments.VariantMetrics;
import money.hejje.backtest.experiments.VariantStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExperimentStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ExperimentStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Experiment e) {
        jdbc.sql("""
                INSERT INTO experiment (id, base_version_id, strategy_id, goal, dataset, splits, status, created_by, created_by_session, created_at, notes)
                VALUES (:id, :base, :strategy, :goal, CAST(:dataset AS jsonb), CAST(:splits AS jsonb), :status, :by, :session, :created, CAST(:notes AS jsonb))
                """).param("id", e.id()).param("base", e.baseVersionId()).param("strategy", e.strategyId()).param("goal", e.goal()).param("dataset", write(e.dataset()))
                .param("splits", write(e.splits())).param("status", e.status().name()).param("by", e.createdBy()).param("session", e.createdBySession())
                .param("created", utc(e.createdAt())).param("notes", write(e.notes())).update();
        for (Variant v : e.variants()) {
            jdbc.sql("""
                    INSERT INTO experiment_variant (id, experiment_id, ordinal, name, description, delta, definition_yaml, status, warnings, parameter_count,
                                                    condition_count, error)
                    VALUES (:id, :experiment, :ordinal, :name, :description, CAST(:delta AS jsonb), :yaml, :status, CAST(:warnings AS jsonb), :params, :conditions, :error)
                    """).param("id", v.id()).param("experiment", e.id()).param("ordinal", v.ordinal()).param("name", v.name()).param("description", v.description())
                    .param("delta", write(v.delta())).param("yaml", v.definitionYaml()).param("status", v.status().name()).param("warnings", write(v.warnings()))
                    .param("params", v.parameterCount()).param("conditions", v.conditionCount()).param("error", v.error()).update();
        }
    }

    public void markRunning(UUID id) {
        jdbc.sql("UPDATE experiment SET status = 'RUNNING' WHERE id = :id").param("id", id).update();
    }

    public void completeVariant(UUID id, VariantMetrics metrics) {
        jdbc.sql("UPDATE experiment_variant SET status = 'DONE', metrics = CAST(:m AS jsonb) WHERE id = :id").param("id", id).param("m", write(metrics)).update();
    }

    public void failVariant(UUID id, String error) {
        jdbc.sql("UPDATE experiment_variant SET status = 'FAILED', error = :error WHERE id = :id").param("id", id).param("error", error).update();
    }

    public void rankVariant(UUID experimentId, String name, int rank, double score, String verdict, List<String> warnings) {
        jdbc.sql("""
                UPDATE experiment_variant SET rank = :rank, score = :score, verdict = :verdict, warnings = CAST(:warnings AS jsonb)
                WHERE experiment_id = :e AND name = :name
                """).param("e", experimentId).param("name", name).param("rank", rank).param("score", score).param("verdict", verdict)
                .param("warnings", write(warnings)).update();
    }

    public void finish(UUID id, ExperimentStatus status, List<String> notes, String error, Instant at) {
        jdbc.sql("UPDATE experiment SET status = :status, notes = CAST(:notes AS jsonb), error = :error, finished_at = :at WHERE id = :id")
                .param("id", id).param("status", status.name()).param("notes", write(notes)).param("error", error).param("at", utc(at)).update();
    }

    public int failUnfinished(String error, Instant at) {
        return jdbc.sql("UPDATE experiment SET status = 'FAILED', error = :error, finished_at = :at WHERE status IN ('QUEUED', 'RUNNING')")
                .param("error", error).param("at", utc(at)).update();
    }

    public void promoted(UUID variantId, UUID versionId) {
        jdbc.sql("UPDATE experiment_variant SET promoted_version_id = :v WHERE id = :id").param("id", variantId).param("v", versionId).update();
    }

    public Optional<Experiment> find(UUID id) {
        return jdbc.sql("SELECT * FROM experiment WHERE id = :id").param("id", id).query(this::experiment).optional();
    }

    public List<Experiment> list(UUID baseVersionId, int limit) {
        if (baseVersionId == null) {
            return jdbc.sql("SELECT * FROM experiment ORDER BY created_at DESC LIMIT :n").param("n", limit).query(this::experiment).list();
        }
        return jdbc.sql("SELECT * FROM experiment WHERE base_version_id = :v ORDER BY created_at DESC LIMIT :n").param("v", baseVersionId).param("n", limit)
                .query(this::experiment).list();
    }

    private List<Variant> variants(UUID experimentId) {
        return jdbc.sql("SELECT * FROM experiment_variant WHERE experiment_id = :e ORDER BY COALESCE(rank, 1000), ordinal").param("e", experimentId)
                .query(this::variant).list();
    }

    private Experiment experiment(ResultSet rs, int i) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        return new Experiment(id, rs.getObject("base_version_id", UUID.class), rs.getObject("strategy_id", UUID.class), rs.getString("goal"),
                read(rs.getString("dataset"), new TypeReference<Dataset>() {}), read(rs.getString("splits"), new TypeReference<Splits>() {}),
                ExperimentStatus.valueOf(rs.getString("status")), rs.getString("created_by"), rs.getObject("created_by_session", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), finished == null ? null : finished.toInstant(), rs.getString("error"),
                read(rs.getString("notes"), new TypeReference<List<String>>() {}), variants(id));
    }

    private Variant variant(ResultSet rs, int i) throws SQLException {
        Object rank = rs.getObject("rank");
        Object score = rs.getObject("score");
        String metrics = rs.getString("metrics");
        return new Variant(rs.getObject("id", UUID.class), rs.getObject("experiment_id", UUID.class), rs.getInt("ordinal"), rs.getString("name"),
                rs.getString("description"), read(rs.getString("delta"), new TypeReference<Map<String, Object>>() {}), rs.getString("definition_yaml"),
                VariantStatus.valueOf(rs.getString("status")), metrics == null ? null : read(metrics, new TypeReference<VariantMetrics>() {}),
                rank == null ? null : ((Number) rank).intValue(), score == null ? null : ((Number) score).doubleValue(), rs.getString("verdict"),
                read(rs.getString("warnings"), new TypeReference<List<String>>() {}), rs.getInt("parameter_count"), rs.getInt("condition_count"), rs.getString("error"),
                rs.getObject("promoted_version_id", UUID.class));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return text == null ? null : json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable experiment row: " + e.getMessage(), e);
        }
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
