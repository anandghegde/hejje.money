package money.hejje.harness.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.harness.SimReport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SimReportStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    SimReportStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Inserts the report; false when the (session, bot) report exists (imports are idempotent). */
    public boolean insert(SimReport r) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", r.id());
        p.put("session", r.sessionId());
        p.put("name", r.botName());
        p.put("version", r.botVersion());
        p.put("kind", r.botKind());
        p.put("dates", write(r.sessionDates().stream().map(LocalDate::toString).toList()));
        p.put("capital", r.capitalPaise());
        p.put("trades", r.trades());
        p.put("wins", r.wins());
        p.put("exp", r.expectancyR());
        p.put("pf", r.profitFactor());
        p.put("dd", r.maxDrawdownPaise());
        p.put("net", r.netPnlPaise());
        p.put("win", r.winPaise());
        p.put("loss", r.lossPaise());
        p.put("friction", r.frictionPaise());
        p.put("rs", write(r.tradeRs()));
        p.put("dhash", r.decisionsHash());
        p.put("rhash", r.resultHash());
        p.put("snapshot", write(r.snapshot()));
        p.put("at", r.createdAt().atOffset(ZoneOffset.UTC));
        return jdbc.sql("""
                INSERT INTO sim_report (id, session_id, bot_name, bot_version, bot_kind, session_dates, capital_paise, trades, wins, expectancy_r, profit_factor,
                    max_drawdown_paise, net_pnl_paise, win_paise, loss_paise, friction_paise, trade_rs, decisions_hash, result_hash, snapshot, created_at)
                VALUES (:id, :session, :name, :version, :kind, CAST(:dates AS jsonb), :capital, :trades, :wins, :exp, :pf, :dd, :net, :win, :loss, :friction,
                    CAST(:rs AS jsonb), :dhash, :rhash, CAST(:snapshot AS jsonb), :at)
                ON CONFLICT (session_id, bot_name) DO NOTHING
                """).params(p).update() == 1;
    }

    public Optional<SimReport> find(UUID id) {
        return jdbc.sql("SELECT * FROM sim_report WHERE id = :id").param("id", id).query(this::map).optional();
    }

    /** Newest first; {@code botName}/{@code version} optional filters. */
    public List<SimReport> list(String botName, String version, int limit) {
        return jdbc.sql("""
                SELECT * FROM sim_report WHERE (CAST(:name AS text) IS NULL OR bot_name = :name) AND (CAST(:version AS text) IS NULL OR bot_version = :version)
                ORDER BY created_at DESC LIMIT :limit
                """).param("name", botName).param("version", version).param("limit", limit).query(this::map).list();
    }

    private SimReport map(ResultSet rs, int i) throws SQLException {
        Object exp = rs.getObject("expectancy_r");
        Object pf = rs.getObject("profit_factor");
        List<String> dates = read(rs.getString("session_dates"), new TypeReference<List<String>>() {});
        return new SimReport(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class), rs.getString("bot_name"), rs.getString("bot_version"),
                rs.getString("bot_kind"), dates.stream().map(LocalDate::parse).toList(), rs.getLong("capital_paise"), rs.getInt("trades"), rs.getInt("wins"),
                exp == null ? null : ((Number) exp).doubleValue(), pf == null ? null : ((Number) pf).doubleValue(), rs.getLong("max_drawdown_paise"),
                rs.getLong("net_pnl_paise"), rs.getLong("win_paise"), rs.getLong("loss_paise"), rs.getLong("friction_paise"),
                read(rs.getString("trade_rs"), new TypeReference<List<Double>>() {}), rs.getString("decisions_hash"), rs.getString("result_hash"),
                read(rs.getString("snapshot"), new TypeReference<Map<String, Object>>() {}), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
