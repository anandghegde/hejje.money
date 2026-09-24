package money.hejje.analogs.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import money.hejje.analogs.AnalogKind;
import money.hejje.analogs.AnalogMatch;
import money.hejje.analogs.AnalogSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code analog_summary} (written once per key, kept forever) and {@code analog_match} (one JSON document per key, pruned by date). */
@Repository
public class AnalogStore {

    private static final TypeReference<List<AnalogMatch>> MATCHES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AnalogStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Returns false when the key already has a summary (nothing is overwritten). */
    boolean insert(AnalogSummary s, List<AnalogMatch> matches) {
        try {
            int written = jdbc.sql("""
                    INSERT INTO analog_summary (session_date, instrument_id, kind, lookback, checkpoint, engine_version, symbol, summary)
                    VALUES (:date, :id, :kind, :lookback, :checkpoint, :v, :symbol, CAST(:summary AS jsonb)) ON CONFLICT DO NOTHING
                    """)
                    .param("date", s.sessionDate()).param("id", s.instrumentId()).param("kind", s.kind().name()).param("lookback", s.lookback())
                    .param("checkpoint", s.checkpoint()).param("v", s.engineVersion()).param("symbol", s.symbol())
                    .param("summary", json.writeValueAsString(s)).update();
            if (written == 0) {
                return false;
            }
            jdbc.sql("""
                    INSERT INTO analog_match (session_date, instrument_id, kind, lookback, checkpoint, engine_version, matches)
                    VALUES (:date, :id, :kind, :lookback, :checkpoint, :v, CAST(:matches AS jsonb)) ON CONFLICT DO NOTHING
                    """)
                    .param("date", s.sessionDate()).param("id", s.instrumentId()).param("kind", s.kind().name()).param("lookback", s.lookback())
                    .param("checkpoint", s.checkpoint()).param("v", s.engineVersion()).param("matches", json.writeValueAsString(matches)).update();
            return true;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    boolean exists(LocalDate date, AnalogKind kind, int lookback, String version) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM analog_summary WHERE session_date = :d AND kind = :k AND lookback = :l AND engine_version = :v)")
                .param("d", date).param("k", kind.name()).param("l", lookback).param("v", version).query(Boolean.class).single();
    }

    boolean existsCheckpoint(LocalDate date, String checkpoint, String version) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM analog_summary WHERE session_date = :d AND kind = 'SESSION' AND checkpoint = :c AND engine_version = :v)")
                .param("d", date).param("c", checkpoint).param("v", version).query(Boolean.class).single();
    }

    /** The newest session on or before {@code cap} with a summary of the kind. */
    Optional<LocalDate> latest(LocalDate cap, AnalogKind kind, String version) {
        return jdbc.sql("SELECT max(session_date) FROM analog_summary WHERE kind = :k AND engine_version = :v AND session_date <= :cap")
                .param("k", kind.name()).param("v", version).param("cap", cap).query(LocalDate.class).optional();
    }

    Optional<AnalogSummary> find(String symbol, LocalDate date, AnalogKind kind, int lookback, String checkpoint, String version) {
        return jdbc.sql("""
                SELECT summary FROM analog_summary WHERE symbol = :s AND session_date = :d AND kind = :k AND lookback = :l AND checkpoint = :c
                  AND engine_version = :v
                """)
                .param("s", symbol).param("d", date).param("k", kind.name()).param("l", lookback).param("c", checkpoint).param("v", version)
                .query((rs, i) -> read(rs.getString(1))).optional();
    }

    /** Every summary of a session, kind and engine version (all lookbacks and checkpoints), ordered for a stable hash. */
    List<AnalogSummary> forDate(LocalDate date, AnalogKind kind, String version) {
        return jdbc.sql("""
                SELECT summary FROM analog_summary WHERE session_date = :d AND kind = :k AND engine_version = :v
                ORDER BY instrument_id, lookback, checkpoint
                """)
                .param("d", date).param("k", kind.name()).param("v", version).query((rs, i) -> read(rs.getString(1))).list();
    }

    Optional<List<AnalogMatch>> matches(String symbol, LocalDate date, AnalogKind kind, int lookback, String checkpoint, String version) {
        return jdbc.sql("""
                SELECT m.matches FROM analog_match m JOIN analog_summary s USING (session_date, instrument_id, kind, lookback, checkpoint, engine_version)
                WHERE s.symbol = :s AND m.session_date = :d AND m.kind = :k AND m.lookback = :l AND m.checkpoint = :c AND m.engine_version = :v
                """)
                .param("s", symbol).param("d", date).param("k", kind.name()).param("l", lookback).param("c", checkpoint).param("v", version)
                .query((rs, i) -> {
                    try {
                        return json.readValue(rs.getString(1), MATCHES);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                }).optional();
    }

    int pruneMatches(LocalDate before) {
        return jdbc.sql("DELETE FROM analog_match WHERE session_date < :d").param("d", before).update();
    }

    private AnalogSummary read(String text) {
        try {
            return json.readValue(text, AnalogSummary.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
