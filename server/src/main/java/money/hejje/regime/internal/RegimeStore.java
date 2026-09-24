package money.hejje.regime.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.common.Ids;
import money.hejje.regime.Breadth;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.IntradayStructure;
import money.hejje.regime.Opening;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.regime.Trend;
import money.hejje.regime.Volatility;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code market_regime} (one final row per session and classifier version) and {@code market_regime_intraday} (snapshots). */
@Repository
public class RegimeStore {

    private static final TypeReference<Map<String, Object>> FEATURES = new TypeReference<>() {};
    private static final TypeReference<List<String>> EVIDENCE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    RegimeStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void upsertFinal(RegimeSnapshot s, java.time.Instant computedAt) {
        jdbc.sql("""
                INSERT INTO market_regime (session_date, classifier_version, as_of, trend, volatility, opening, breadth, intraday_structure, event_environment,
                                           market_condition, features, evidence, computed_at)
                VALUES (:date, :version, :asOf, :trend, :volatility, :opening, :breadth, :structure, :environment, :condition,
                        CAST(:features AS jsonb), CAST(:evidence AS jsonb), :computedAt)
                ON CONFLICT (session_date, classifier_version) DO UPDATE SET as_of = EXCLUDED.as_of, trend = EXCLUDED.trend,
                    volatility = EXCLUDED.volatility, opening = EXCLUDED.opening, breadth = EXCLUDED.breadth,
                    intraday_structure = EXCLUDED.intraday_structure, event_environment = EXCLUDED.event_environment, market_condition = EXCLUDED.market_condition,
                    features = EXCLUDED.features, evidence = EXCLUDED.evidence, computed_at = EXCLUDED.computed_at
                """)
                .params(params(s)).param("computedAt", computedAt.atOffset(ZoneOffset.UTC)).update();
    }

    public void insertIntraday(RegimeSnapshot s, java.time.Instant computedAt) {
        jdbc.sql("""
                INSERT INTO market_regime_intraday (id, session_date, classifier_version, as_of, trend, volatility, opening, breadth, intraday_structure,
                                                    event_environment, market_condition, features, evidence, computed_at)
                VALUES (:id, :date, :version, :asOf, :trend, :volatility, :opening, :breadth, :structure, :environment, :condition, CAST(:features AS jsonb),
                        CAST(:evidence AS jsonb), :computedAt)
                """)
                .params(params(s)).param("id", Ids.newId()).param("computedAt", computedAt.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<RegimeSnapshot> findFinal(LocalDate date, String version) {
        return jdbc.sql("SELECT * FROM market_regime WHERE session_date = :d AND classifier_version = :v").param("d", date).param("v", version)
                .query(this::map).optional();
    }

    public List<RegimeSnapshot> finals(LocalDate from, LocalDate to, String version) {
        return jdbc.sql("SELECT * FROM market_regime WHERE classifier_version = :v AND session_date BETWEEN :f AND :t ORDER BY session_date")
                .param("v", version).param("f", from).param("t", to).query(this::map).list();
    }

    public int countFinals(LocalDate from, LocalDate to, String version) {
        return jdbc.sql("SELECT count(*) FROM market_regime WHERE classifier_version = :v AND session_date BETWEEN :f AND :t")
                .param("v", version).param("f", from).param("t", to).query(Integer.class).single();
    }

    public List<RegimeSnapshot> intraday(LocalDate date, String version) {
        return jdbc.sql("SELECT * FROM market_regime_intraday WHERE session_date = :d AND classifier_version = :v ORDER BY as_of")
                .param("d", date).param("v", version).query(this::map).list();
    }

    private Map<String, Object> params(RegimeSnapshot s) {
        try {
            Map<String, Object> p = new java.util.HashMap<>();
            p.put("date", s.date());
            p.put("version", s.classifierVersion());
            p.put("asOf", s.asOf().atOffset(ZoneOffset.UTC));
            p.put("trend", s.trend().name());
            p.put("volatility", s.volatility().name());
            p.put("opening", s.opening().name());
            p.put("breadth", s.breadth().name());
            p.put("structure", s.intradayStructure().name());
            p.put("environment", s.eventEnvironment().name());
            p.put("condition", s.marketCondition().name());
            p.put("features", json.writeValueAsString(s.features()));
            p.put("evidence", json.writeValueAsString(s.evidence()));
            return p;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private RegimeSnapshot map(ResultSet rs, int i) throws SQLException {
        try {
            boolean isFinal = hasColumn(rs, "id") ? false : true;
            return new RegimeSnapshot(rs.getObject("session_date", LocalDate.class), rs.getObject("as_of", OffsetDateTime.class).toInstant(),
                    Trend.valueOf(rs.getString("trend")), Volatility.valueOf(rs.getString("volatility")), Opening.valueOf(rs.getString("opening")),
                    Breadth.valueOf(rs.getString("breadth")), IntradayStructure.valueOf(rs.getString("intraday_structure")),
                    EventEnvironment.valueOf(rs.getString("event_environment")),
                    money.hejje.regime.MarketCondition.valueOf(rs.getString("market_condition")), json.readValue(rs.getString("features"), FEATURES),
                    json.readValue(rs.getString("evidence"), EVIDENCE), rs.getString("classifier_version"), isFinal);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean hasColumn(ResultSet rs, String name) throws SQLException {
        var meta = rs.getMetaData();
        for (int c = 1; c <= meta.getColumnCount(); c++) {
            if (meta.getColumnLabel(c).equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
