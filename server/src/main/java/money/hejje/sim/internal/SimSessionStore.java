package money.hejje.sim.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Money;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SimSessionStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    SimSessionStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(SimSession s) {
        jdbc.sql("""
                INSERT INTO sim_session (id, spec, state, speed, day_index, days, session_date, step, fills, friction_paise, net_pnl_paise, result_hash, error,
                    created_by, created_at, finished_at, updated_at, warnings)
                VALUES (:id, CAST(:spec AS jsonb), :state, :speed, :dayIndex, :days, :date, :step, :fills, :friction, :net, :hash, :error, :by, :createdAt,
                    :finishedAt, :updatedAt, CAST(:warnings AS jsonb))
                """).params(params(s)).update();
    }

    public void update(SimSession s) {
        jdbc.sql("""
                UPDATE sim_session SET state = :state, speed = :speed, day_index = :dayIndex, session_date = :date, step = :step, fills = :fills,
                    friction_paise = :friction, net_pnl_paise = :net, result_hash = :hash, error = :error, finished_at = :finishedAt, updated_at = :updatedAt
                WHERE id = :id
                """).params(params(s)).update();
    }

    public Optional<SimSession> find(UUID id) {
        return jdbc.sql("SELECT * FROM sim_session WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public List<SimSession> list(int limit) {
        return jdbc.sql("SELECT * FROM sim_session ORDER BY created_at DESC LIMIT :limit").param("limit", limit).query(this::map).list();
    }

    /** Sessions left PAUSED or PLAYING by a previous process cannot resume (the replay state lived in memory). */
    public int failInterrupted(Instant now) {
        return jdbc.sql("UPDATE sim_session SET state = 'FAILED', error = 'interrupted by a restart', finished_at = :now, updated_at = :now "
                + "WHERE state IN ('PAUSED', 'PLAYING')").param("now", ts(now)).update();
    }

    private java.util.Map<String, Object> params(SimSession s) {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("id", s.id());
        try {
            p.put("spec", json.writeValueAsString(s.spec()));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        p.put("state", s.state().name());
        p.put("speed", s.speed().label());
        p.put("dayIndex", s.dayIndex());
        p.put("days", s.days());
        p.put("date", s.sessionDate());
        p.put("step", s.step());
        p.put("fills", s.fills());
        p.put("friction", s.friction().paise());
        p.put("net", s.netPnl().paise());
        p.put("hash", s.resultHash());
        p.put("error", s.error());
        p.put("by", s.createdBy());
        p.put("createdAt", ts(s.createdAt()));
        p.put("finishedAt", s.finishedAt() == null ? null : ts(s.finishedAt()));
        p.put("updatedAt", ts(s.updatedAt()));
        try {
            p.put("warnings", json.writeValueAsString(s.warnings()));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return p;
    }

    private SimSession map(ResultSet rs, int i) throws SQLException {
        SimSessionSpec spec;
        try {
            spec = json.readValue(rs.getString("spec"), SimSessionSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        LocalDate date = rs.getObject("session_date", LocalDate.class);
        return new SimSession(rs.getObject("id", UUID.class), spec, SimSession.State.valueOf(rs.getString("state")), SimSession.Speed.of(rs.getString("speed")),
                rs.getInt("day_index"), rs.getInt("days"), date, rs.getInt("step"), rs.getInt("fills"), Money.ofPaise(rs.getLong("friction_paise")),
                Money.ofPaise(rs.getLong("net_pnl_paise")), rs.getString("result_hash"), rs.getString("error"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "finished_at"), instant(rs, "updated_at"), warnings(rs.getString("warnings")));
    }

    private List<String> warnings(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
