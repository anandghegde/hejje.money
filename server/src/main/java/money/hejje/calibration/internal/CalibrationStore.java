package money.hejje.calibration.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.calibration.LabelRule;
import money.hejje.calibration.Prediction;
import money.hejje.common.Side;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CalibrationStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    CalibrationStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A recorded prediction and its label state. */
    public record Row(Prediction prediction, String horizon, LocalDate sessionDate, String outcome) {}

    /** Records a prediction as PENDING; false when it was recorded before. */
    public boolean insert(Prediction p, String horizon, LocalDate sessionDate) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", p.source());
        m.put("sid", p.sourceId());
        m.put("key", p.key());
        m.put("horizon", horizon);
        m.put("purpose", p.purpose());
        m.put("version", p.version());
        m.put("p", p.probability());
        m.put("instrument", p.instrumentId());
        m.put("rule", p.rule().name());
        m.put("side", p.side().name());
        m.put("stop", p.stop());
        m.put("at", p.decidedAt().atOffset(ZoneOffset.UTC));
        m.put("date", sessionDate);
        return jdbc.sql("""
                INSERT INTO calibration_label (source, source_id, key, horizon, purpose, version, probability, instrument_id, rule, side, stop, decided_at,
                    session_date, outcome)
                VALUES (:source, :sid, :key, :horizon, :purpose, :version, :p, :instrument, :rule, :side, :stop, :at, :date, 'PENDING')
                ON CONFLICT DO NOTHING
                """).params(m).update() == 1;
    }

    public List<Row> pending(Instant before, int limit) {
        return jdbc.sql("SELECT * FROM calibration_label WHERE outcome = 'PENDING' AND decided_at < :before ORDER BY decided_at LIMIT :limit")
                .param("before", before.atOffset(ZoneOffset.UTC)).param("limit", limit).query(this::row).list();
    }

    /** Labels a pending row; a labelled row is never changed. */
    public boolean label(Row r, String outcome, Map<String, Object> evidence, Instant at) {
        Prediction p = r.prediction();
        return jdbc.sql("""
                UPDATE calibration_label SET outcome = :outcome, label = :label, evidence = CAST(:evidence AS jsonb), labelled_at = :at
                WHERE source = :source AND source_id = :sid AND key = :key AND horizon = :horizon AND outcome = 'PENDING'
                """).param("outcome", outcome).param("label", "HIT".equals(outcome) ? Integer.valueOf(1) : "MISS".equals(outcome) ? Integer.valueOf(0) : null)
                .param("evidence", write(evidence)).param("at", at.atOffset(ZoneOffset.UTC)).param("source", p.source()).param("sid", p.sourceId())
                .param("key", p.key()).param("horizon", r.horizon()).update() == 1;
    }

    /** One labelled or pending prediction for a report. */
    public record Point(double probability, String outcome, LocalDate sessionDate) {}

    public List<Point> points(String purpose, String version, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT probability, outcome, session_date FROM calibration_label
                WHERE purpose = :purpose AND (CAST(:version AS text) IS NULL OR version = :version)
                  AND (CAST(:from AS date) IS NULL OR session_date >= :from) AND (CAST(:to AS date) IS NULL OR session_date <= :to)
                """).param("purpose", purpose).param("version", version).param("from", from).param("to", to)
                .query((rs, i) -> new Point(rs.getDouble(1), rs.getString(2), rs.getObject(3, LocalDate.class))).list();
    }

    public record PurposeRow(String purpose, String version, int predictions, int labelled, LocalDate first, LocalDate last) {}

    public List<PurposeRow> purposes() {
        return jdbc.sql("""
                SELECT purpose, version, count(*), count(*) FILTER (WHERE outcome IN ('HIT', 'MISS')), min(session_date), max(session_date)
                FROM calibration_label GROUP BY purpose, version ORDER BY purpose, version
                """).query((rs, i) -> new PurposeRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getObject(5, LocalDate.class),
                rs.getObject(6, LocalDate.class))).list();
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        Prediction p = new Prediction(rs.getString("source"), rs.getString("source_id"), rs.getString("key"), rs.getString("purpose"), rs.getString("version"),
                rs.getDouble("probability"), rs.getObject("instrument_id", UUID.class), LabelRule.valueOf(rs.getString("rule")), Side.valueOf(rs.getString("side")),
                rs.getObject("stop", BigDecimal.class), rs.getObject("decided_at", OffsetDateTime.class).toInstant());
        return new Row(p, rs.getString("horizon"), rs.getObject("session_date", LocalDate.class), rs.getString("outcome"));
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
