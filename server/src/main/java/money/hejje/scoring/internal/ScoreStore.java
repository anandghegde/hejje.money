package money.hejje.scoring.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreBreakdown;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScoreStore {

    private static final TypeReference<List<ScoreBreakdown.Component>> COMPONENTS = new TypeReference<>() {};
    private static final TypeReference<List<Adjustment>> ADJUSTMENTS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ScoreStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(ScoreBreakdown s) {
        try {
            jdbc.sql("""
                    INSERT INTO strategy_score (id, version_id, instrument_id, computed_at, base_backtest_id, base, cap, components, adjustments, final)
                    VALUES (:id, :versionId, :instrumentId, :computedAt, :backtestId, :base, :cap, CAST(:components AS jsonb), CAST(:adjustments AS jsonb), :final)
                    """)
                    .param("id", s.id()).param("versionId", s.versionId()).param("instrumentId", s.instrumentId(), java.sql.Types.OTHER)
                    .param("computedAt", s.computedAt().atOffset(ZoneOffset.UTC)).param("backtestId", s.baseBacktestId(), java.sql.Types.OTHER)
                    .param("base", s.base()).param("cap", s.cap()).param("components", json.writeValueAsString(s.components()))
                    .param("adjustments", json.writeValueAsString(s.adjustments())).param("final", s.finalScore()).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Optional<ScoreBreakdown> latest(UUID versionId, UUID instrumentId) {
        String sql = "SELECT * FROM strategy_score WHERE version_id = :v AND " + (instrumentId == null ? "instrument_id IS NULL" : "instrument_id = :i")
                + " ORDER BY computed_at DESC LIMIT 1";
        var spec = jdbc.sql(sql).param("v", versionId);
        if (instrumentId != null) {
            spec = spec.param("i", instrumentId);
        }
        return spec.query(this::map).optional();
    }

    public List<ScoreBreakdown> latestForVersion(UUID versionId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (instrument_id) * FROM strategy_score WHERE version_id = :v ORDER BY instrument_id, computed_at DESC
                """).param("v", versionId).query(this::map).list();
    }

    private ScoreBreakdown map(ResultSet rs, int i) throws SQLException {
        try {
            Object instrument = rs.getObject("instrument_id");
            Object backtest = rs.getObject("base_backtest_id");
            return new ScoreBreakdown(rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class), instrument == null ? null : (UUID) instrument,
                    rs.getObject("computed_at", OffsetDateTime.class).toInstant(), backtest == null ? null : (UUID) backtest, rs.getDouble("base"),
                    rs.getString("cap"), json.readValue(rs.getString("components"), COMPONENTS), json.readValue(rs.getString("adjustments"), ADJUSTMENTS),
                    rs.getInt("final"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
