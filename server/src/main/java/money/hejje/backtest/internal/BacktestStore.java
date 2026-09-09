package money.hejje.backtest.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestResult;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.BacktestStatus;
import money.hejje.backtest.BacktestTrade;
import money.hejje.backtest.Engine;
import money.hejje.backtest.ExitReason;
import money.hejje.backtest.QualityWarning;
import money.hejje.backtest.Split;
import money.hejje.backtest.WalkForwardWindow;
import money.hejje.common.Money;
import money.hejje.common.Side;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BacktestStore {

    private static final TypeReference<Map<Split, BacktestMetrics>> BY_SPLIT = new TypeReference<>() {};
    private static final TypeReference<List<WalkForwardWindow>> WINDOWS = new TypeReference<>() {};
    private static final TypeReference<List<QualityWarning>> WARNINGS = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> EVIDENCE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    BacktestStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Backtest b) {
        jdbc.sql("""
                INSERT INTO backtest (id, version_id, spec, status, progress_pct, created_at, engine, created_by)
                VALUES (:id, :versionId, CAST(:spec AS jsonb), :status, 0, :createdAt, :engine, :createdBy)
                """)
                .param("id", b.id()).param("versionId", b.versionId()).param("spec", write(b.spec())).param("status", b.status().name())
                .param("createdAt", b.createdAt().atOffset(ZoneOffset.UTC)).param("engine", b.engine().name()).param("createdBy", b.createdBy()).update();
    }

    public void markRunning(UUID id, Instant at) {
        jdbc.sql("UPDATE backtest SET status = 'RUNNING', started_at = :at WHERE id = :id").param("at", at.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }

    public void progress(UUID id, int pct) {
        jdbc.sql("UPDATE backtest SET progress_pct = :pct WHERE id = :id AND status = 'RUNNING'").param("pct", pct).param("id", id).update();
    }

    public void finish(UUID id, BacktestResult result, Instant at) {
        jdbc.sql("""
                UPDATE backtest SET status = 'DONE', progress_pct = 100, finished_at = :at, metrics = CAST(:metrics AS jsonb),
                    by_split = CAST(:bySplit AS jsonb), windows = CAST(:windows AS jsonb), warnings = CAST(:warnings AS jsonb),
                    sessions_expected = :expected, sessions_with_data = :withData, skipped_signals = :skipped, result_hash = :hash
                WHERE id = :id
                """)
                .param("at", at.atOffset(ZoneOffset.UTC)).param("metrics", write(result.overall())).param("bySplit", write(result.bySplit()))
                .param("windows", write(result.windows())).param("warnings", write(result.warnings())).param("expected", result.sessionsExpected())
                .param("withData", result.sessionsWithData()).param("skipped", result.skippedSignals()).param("hash", result.resultHash())
                .param("id", id).update();
    }

    public void fail(UUID id, BacktestStatus status, String error, Instant at) {
        jdbc.sql("UPDATE backtest SET status = :status, finished_at = :at, error = :error WHERE id = :id")
                .param("status", status.name()).param("at", at.atOffset(ZoneOffset.UTC)).param("error", error).param("id", id).update();
    }

    /** Marks every QUEUED/RUNNING backtest as FAILED (used after a restart). Returns the count. */
    public int failUnfinished(String reason, Instant at) {
        return jdbc.sql("UPDATE backtest SET status = 'FAILED', finished_at = :at, error = :error WHERE status IN ('QUEUED', 'RUNNING')")
                .param("at", at.atOffset(ZoneOffset.UTC)).param("error", reason).update();
    }

    public Optional<Backtest> find(UUID id) {
        return jdbc.sql("SELECT * FROM backtest WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public List<Backtest> findByVersion(UUID versionId) {
        return jdbc.sql("SELECT * FROM backtest WHERE version_id = :v ORDER BY created_at DESC").param("v", versionId).query(this::map).list();
    }

    public List<Backtest> findRecent(int limit) {
        return jdbc.sql("SELECT * FROM backtest ORDER BY created_at DESC LIMIT :limit").param("limit", limit).query(this::map).list();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM backtest WHERE id = :id").param("id", id).update();
    }

    public void insertTrades(UUID backtestId, List<BacktestTrade> trades) {
        for (BacktestTrade t : trades) {
            jdbc.sql("""
                    INSERT INTO backtest_trade (id, backtest_id, instrument_id, split, entry_time, exit_time, side, qty, entry_price, exit_price,
                        stop, target, gross_paise, costs_paise, net_paise, r_multiple, exit_reason, evidence)
                    VALUES (:id, :backtestId, :instrumentId, :split, :entryTime, :exitTime, :side, :qty, :entryPrice, :exitPrice,
                        :stop, :target, :gross, :costs, :net, :r, :reason, CAST(:evidence AS jsonb))
                    """)
                    .param("id", t.id()).param("backtestId", backtestId).param("instrumentId", t.instrumentId()).param("split", t.split().name())
                    .param("entryTime", t.entryTime().atOffset(ZoneOffset.UTC)).param("exitTime", t.exitTime().atOffset(ZoneOffset.UTC))
                    .param("side", t.side().name()).param("qty", t.qty()).param("entryPrice", t.entryPrice()).param("exitPrice", t.exitPrice())
                    .param("stop", t.stop()).param("target", t.target()).param("gross", t.grossPnl().paise()).param("costs", t.costs().paise())
                    .param("net", t.netPnl().paise()).param("r", t.rMultiple()).param("reason", t.exitReason().name())
                    .param("evidence", write(t.evidence())).update();
        }
    }

    public List<BacktestTrade> findTrades(UUID backtestId, Split split) {
        String sql = "SELECT * FROM backtest_trade WHERE backtest_id = :id" + (split == null ? "" : " AND split = :split") + " ORDER BY entry_time, instrument_id";
        var spec = jdbc.sql(sql).param("id", backtestId);
        if (split != null) {
            spec = spec.param("split", split.name());
        }
        return spec.query(this::mapTrade).list();
    }

    private Backtest map(ResultSet rs, int i) throws SQLException {
        String metrics = rs.getString("metrics");
        String bySplit = rs.getString("by_split");
        String windows = rs.getString("windows");
        String warnings = rs.getString("warnings");
        return new Backtest(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class), read(rs.getString("spec"), BacktestSpec.class),
                BacktestStatus.valueOf(rs.getString("status")), rs.getInt("progress_pct"), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "finished_at"), metrics == null ? null : read(metrics, BacktestMetrics.class),
                bySplit == null ? Map.of() : read(bySplit, BY_SPLIT), windows == null ? List.of() : read(windows, WINDOWS),
                warnings == null ? List.of() : read(warnings, WARNINGS), rs.getInt("sessions_expected"), rs.getInt("sessions_with_data"),
                rs.getInt("skipped_signals"), rs.getString("result_hash"), Engine.valueOf(rs.getString("engine")), rs.getString("error"),
                rs.getString("created_by"));
    }

    private BacktestTrade mapTrade(ResultSet rs, int i) throws SQLException {
        return new BacktestTrade(rs.getObject("id", UUID.class), rs.getObject("backtest_id", UUID.class), rs.getObject("instrument_id", UUID.class),
                Split.valueOf(rs.getString("split")), instant(rs, "entry_time"), instant(rs, "exit_time"), Side.valueOf(rs.getString("side")),
                rs.getInt("qty"), rs.getBigDecimal("entry_price"), rs.getBigDecimal("exit_price"), rs.getBigDecimal("stop"), rs.getBigDecimal("target"),
                Money.ofPaise(rs.getLong("gross_paise")), Money.ofPaise(rs.getLong("costs_paise")), Money.ofPaise(rs.getLong("net_paise")),
                rs.getDouble("r_multiple"), ExitReason.valueOf(rs.getString("exit_reason")), read(rs.getString("evidence"), EVIDENCE));
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

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("Bad stored JSON for " + type.getSimpleName(), e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("Bad stored JSON", e);
        }
    }

    // keep the import used when trades are empty
    @SuppressWarnings("unused")
    private static List<BacktestTrade> none() {
        return new ArrayList<>();
    }
}
