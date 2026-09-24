package money.hejje.ratings.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.ratings.DailyRating;
import money.hejje.ratings.GroupRank;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code daily_rating} and {@code industry_group_rank}: written once per (date, key, engine version), never updated. */
@Repository
class RatingsStore {

    private static final TypeReference<Map<String, Object>> EVIDENCE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final NamedParameterJdbcTemplate named;

    RatingsStore(JdbcClient jdbc, ObjectMapper json, NamedParameterJdbcTemplate named) {
        this.named = named;
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean exists(LocalDate date, String version) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM daily_rating WHERE session_date = :d AND engine_version = :v)")
                .param("d", date).param("v", version).query(Boolean.class).single();
    }

    void insert(List<DailyRating> ratings, List<GroupRank> groups) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[ratings.size()];
        for (int k = 0; k < batch.length; k++) {
            DailyRating r = ratings.get(k);
            try {
                batch[k] = new MapSqlParameterSource().addValue("date", r.sessionDate()).addValue("id", r.instrumentId())
                        .addValue("version", r.engineVersion()).addValue("symbol", r.symbol()).addValue("rsRaw", r.rsRaw(), Types.DOUBLE)
                        .addValue("rsRating", r.rsRating(), Types.SMALLINT).addValue("adRaw", r.adRaw(), Types.DOUBLE)
                        .addValue("adGrade", r.adGrade(), Types.VARCHAR).addValue("offHigh", r.offHighPct(), Types.DOUBLE)
                        .addValue("offLow", r.offLowPct(), Types.DOUBLE).addValue("vol", r.volVsAvg50Pct(), Types.DOUBLE)
                        .addValue("upDown", r.upDownVolRatio(), Types.DOUBLE).addValue("turnover", r.avgTurnoverCr(), Types.DOUBLE)
                        .addValue("close", r.close()).addValue("change", r.changePct(), Types.DOUBLE)
                        .addValue("groupId", r.groupId(), Types.VARCHAR).addValue("groupRank", r.groupRank(), Types.SMALLINT)
                        .addValue("composite", r.techComposite(), Types.SMALLINT).addValue("evidence", json.writeValueAsString(r.evidence()));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
        }
        named.batchUpdate("""
                INSERT INTO daily_rating (session_date, instrument_id, engine_version, symbol, rs_raw, rs_rating, ad_raw, ad_grade,
                    off_high_pct, off_low_pct, vol_vs_avg50_pct, up_down_vol_ratio, avg_turnover_cr, close, change_pct, group_id,
                    group_rank, tech_composite, evidence)
                VALUES (:date, :id, :version, :symbol, :rsRaw, :rsRating, :adRaw, :adGrade, :offHigh, :offLow, :vol, :upDown, :turnover,
                    :close, :change, :groupId, :groupRank, :composite, CAST(:evidence AS jsonb))
                ON CONFLICT DO NOTHING
                """, batch);
        for (GroupRank g : groups) {
            jdbc.sql("""
                    INSERT INTO industry_group_rank (session_date, group_id, engine_version, name, rank, strength, members)
                    VALUES (:date, :group, :version, :name, :rank, :strength, :members) ON CONFLICT DO NOTHING
                    """)
                    .param("date", g.sessionDate()).param("group", g.groupId()).param("version", g.engineVersion()).param("name", g.name())
                    .param("rank", g.rank()).param("strength", g.strength()).param("members", g.members()).update();
        }
    }

    /** The newest session with rows on or before {@code cap}. */
    Optional<LocalDate> latest(LocalDate cap, String version) {
        return jdbc.sql("SELECT max(session_date) FROM daily_rating WHERE engine_version = :v AND session_date <= :cap")
                .param("v", version).param("cap", cap).query(LocalDate.class).optional();
    }

    List<DailyRating> forDate(LocalDate date, String version) {
        return jdbc.sql("SELECT * FROM daily_rating WHERE session_date = :d AND engine_version = :v ORDER BY instrument_id")
                .param("d", date).param("v", version).query(this::map).list();
    }

    Optional<DailyRating> find(String symbol, LocalDate date, String version) {
        return jdbc.sql("SELECT * FROM daily_rating WHERE symbol = :s AND session_date = :d AND engine_version = :v")
                .param("s", symbol).param("d", date).param("v", version).query(this::map).optional();
    }

    List<DailyRating> history(String symbol, LocalDate from, LocalDate to, String version) {
        return jdbc.sql("SELECT * FROM daily_rating WHERE symbol = :s AND engine_version = :v AND session_date BETWEEN :f AND :t ORDER BY session_date")
                .param("s", symbol).param("v", version).param("f", from).param("t", to).query(this::map).list();
    }

    List<GroupRank> groups(LocalDate date, String version) {
        return jdbc.sql("SELECT * FROM industry_group_rank WHERE session_date = :d AND engine_version = :v ORDER BY rank")
                .param("d", date).param("v", version)
                .query((rs, i) -> new GroupRank(rs.getObject("session_date", LocalDate.class), rs.getString("group_id"), rs.getString("engine_version"),
                        rs.getString("name"), rs.getInt("rank"), rs.getDouble("strength"), rs.getInt("members")))
                .list();
    }

    private DailyRating map(ResultSet rs, int i) throws SQLException {
        try {
            return new DailyRating(rs.getObject("session_date", LocalDate.class), rs.getObject("instrument_id", java.util.UUID.class),
                    rs.getString("engine_version"), rs.getString("symbol"), rs.getObject("rs_raw", Double.class), integer(rs, "rs_rating"),
                    rs.getObject("ad_raw", Double.class), rs.getString("ad_grade"), rs.getObject("off_high_pct", Double.class),
                    rs.getObject("off_low_pct", Double.class), rs.getObject("vol_vs_avg50_pct", Double.class),
                    rs.getObject("up_down_vol_ratio", Double.class), rs.getObject("avg_turnover_cr", Double.class), rs.getBigDecimal("close"),
                    rs.getObject("change_pct", Double.class), rs.getString("group_id"), integer(rs, "group_rank"), integer(rs, "tech_composite"),
                    json.readValue(rs.getString("evidence"), EVIDENCE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
