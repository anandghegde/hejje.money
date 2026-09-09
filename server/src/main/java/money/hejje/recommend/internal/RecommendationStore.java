package money.hejje.recommend.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.Side;
import money.hejje.recommend.Decision;
import money.hejje.recommend.Recommendation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** History of decisions per signal: a row is written when the decision (or score) for a signal changes. */
@Repository
public class RecommendationStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    RecommendationStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void recordIfChanged(Recommendation r, ExecutionMode mode, Instant now) {
        Optional<String> last = jdbc.sql("SELECT decision || ':' || coalesce(score::text, 'null') FROM recommendation WHERE signal_id = :s ORDER BY computed_at DESC LIMIT 1")
                .param("s", r.signalId()).query(String.class).optional();
        String current = r.decision().name() + ":" + (r.score() == null ? "null" : r.score());
        if (last.isPresent() && last.get().equals(current)) {
            return;
        }
        try {
            jdbc.sql("""
                    INSERT INTO recommendation (id, computed_at, mode, version_id, instrument_id, signal_id, score, decision, direction, entry, stop, target, risk_paise,
                        hard_blocks, evidence, risks)
                    VALUES (:id, :at, :mode, :versionId, :instrumentId, :signalId, :score, :decision, :direction, :entry, :stop, :target, :risk,
                        CAST(:hardBlocks AS jsonb), CAST(:evidence AS jsonb), CAST(:risks AS jsonb))
                    """)
                    .param("id", Ids.newId()).param("at", now.atOffset(ZoneOffset.UTC)).param("mode", mode.name()).param("versionId", r.versionId())
                    .param("instrumentId", r.instrumentId()).param("signalId", r.signalId()).param("score", r.score()).param("decision", r.decision().name())
                    .param("direction", r.direction() == null ? null : r.direction().name()).param("entry", r.entry()).param("stop", r.stop()).param("target", r.target())
                    .param("risk", r.riskRupees() == null ? null : r.riskRupees().movePointRight(2).longValue())
                    .param("hardBlocks", json.writeValueAsString(r.hardBlocks())).param("evidence", json.writeValueAsString(r.supportingEvidence()))
                    .param("risks", json.writeValueAsString(r.risks())).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public List<Recommendation> history(UUID signalId) {
        return jdbc.sql("SELECT * FROM recommendation WHERE signal_id = :s ORDER BY computed_at, id").param("s", signalId).query(this::map).list();
    }

    private Recommendation map(ResultSet rs, int i) throws SQLException {
        try {
            Object score = rs.getObject("score");
            String direction = rs.getString("direction");
            Object risk = rs.getObject("risk_paise");
            return new Recommendation(rs.getObject("version_id", UUID.class), null, null, 0, null, rs.getObject("instrument_id", UUID.class), null,
                    score == null ? null : ((Number) score).intValue(), Decision.valueOf(rs.getString("decision")), direction == null ? null : Side.valueOf(direction),
                    rs.getObject("signal_id", UUID.class), null, null, rs.getBigDecimal("entry"), rs.getBigDecimal("stop"), rs.getBigDecimal("target"), null,
                    risk == null ? null : java.math.BigDecimal.valueOf(((Number) risk).longValue()).movePointLeft(2), null, null, null, "UNKNOWN",
                    json.readValue(rs.getString("hard_blocks"), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                    json.readValue(rs.getString("evidence"), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                    json.readValue(rs.getString("risks"), json.getTypeFactory().constructCollectionType(List.class, String.class)), null, null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
