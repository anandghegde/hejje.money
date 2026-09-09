package money.hejje.pulse.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import money.hejje.common.Ids;
import money.hejje.pulse.MarketPulse;
import money.hejje.pulse.PulseComponent;
import money.hejje.pulse.PulseDirection;
import money.hejje.pulse.PulseSnapshot;
import money.hejje.pulse.PulseStrength;
import money.hejje.pulse.SectorStrength;
import money.hejje.pulse.TechnicalPulse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code market_pulse}: one row per computed snapshot during the session (PRD 16.2 table). */
@Repository
public class PulseStore {

    private static final TypeReference<List<PulseComponent>> COMPONENTS = new TypeReference<>() {};
    private static final TypeReference<List<SectorStrength>> SECTORS = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    PulseStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(PulseSnapshot s) {
        try {
            TechnicalPulse t = s.technical();
            MarketPulse m = s.market();
            jdbc.sql("""
                    INSERT INTO market_pulse (id, session_date, as_of, direction, strength, score, coverage, regime, volatility, breadth, sectors, global_context,
                                              components, evidence)
                    VALUES (:id, :date, :asOf, :direction, :strength, :score, :coverage, :regime, :volatility, :breadth, CAST(:sectors AS jsonb), :global,
                            CAST(:components AS jsonb), CAST(:evidence AS jsonb))
                    """)
                    .param("id", Ids.newId()).param("date", s.date()).param("asOf", s.asOf().atOffset(ZoneOffset.UTC)).param("direction", t.direction().name())
                    .param("strength", t.strength().name()).param("score", t.score()).param("coverage", t.coverage()).param("regime", m.regime())
                    .param("volatility", m.volatility()).param("breadth", m.breadth()).param("sectors", json.writeValueAsString(m.sectors()))
                    .param("global", m.globalContext()).param("components", json.writeValueAsString(t.components()))
                    .param("evidence", json.writeValueAsString(t.evidence())).update();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public List<PulseSnapshot> history(LocalDate date) {
        return jdbc.sql("SELECT * FROM market_pulse WHERE session_date = :d ORDER BY as_of").param("d", date).query(this::map).list();
    }

    private PulseSnapshot map(ResultSet rs, int i) throws SQLException {
        try {
            TechnicalPulse t = new TechnicalPulse(PulseDirection.valueOf(rs.getString("direction")), PulseStrength.valueOf(rs.getString("strength")),
                    rs.getInt("score"), rs.getDouble("coverage"), json.readValue(rs.getString("components"), COMPONENTS),
                    json.readValue(rs.getString("evidence"), STRINGS));
            MarketPulse m = new MarketPulse(rs.getString("regime"), rs.getString("volatility"), rs.getString("breadth"),
                    json.readValue(rs.getString("sectors"), SECTORS), rs.getString("global_context"));
            return new PulseSnapshot(rs.getObject("session_date", LocalDate.class), rs.getObject("as_of", OffsetDateTime.class).toInstant(), t, m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
