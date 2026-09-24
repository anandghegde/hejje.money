package money.hejje.ratings.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.ratings.BaseType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code base} (written once), {@code base_status_history} (append-only) and {@code base_progress}. */
@Repository
class BaseStore {

    private static final TypeReference<Map<String, Object>> EVIDENCE = new TypeReference<>() {};
    /** A base joined with its newest status row on or before {@code :asOf}. */
    private static final String AS_OF = """
            SELECT b.*, h.status, h.status_date, h.trigger_date, h.entry, h.volume_confirmed, h.exit, h.outcome_pct, h.outcome_r
            FROM base b
            JOIN LATERAL (SELECT * FROM base_status_history x WHERE x.base_id = b.id AND x.status_date <= :asOf ORDER BY x.seq DESC LIMIT 1) h ON TRUE
            WHERE b.engine_version = :v
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    BaseStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(Base b) {
        try {
            jdbc.sql("""
                    INSERT INTO base (id, instrument_id, symbol, type, engine_version, start_date, detected_date, depth_pct, base_low, pivot, buy_low,
                        buy_high, stop, goal, evidence)
                    VALUES (:id, :instrument, :symbol, :type, :v, :start, :detected, :depth, :baseLow, :pivot, :buyLow, :buyHigh, :stop, :goal,
                        CAST(:evidence AS jsonb))
                    """)
                    .param("id", b.id()).param("instrument", b.instrumentId()).param("symbol", b.symbol()).param("type", b.type().name())
                    .param("v", b.engineVersion()).param("start", b.startDate()).param("detected", b.detectedDate()).param("depth", b.depthPct())
                    .param("baseLow", b.baseLow()).param("pivot", b.pivot()).param("buyLow", b.buyLow()).param("buyHigh", b.buyHigh())
                    .param("stop", b.stop()).param("goal", b.goal()).param("evidence", json.writeValueAsString(b.evidence())).update();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        appendStatus(b);
    }

    void appendStatus(Base b) {
        jdbc.sql("""
                INSERT INTO base_status_history (base_id, seq, status, status_date, trigger_date, entry, volume_confirmed, exit, outcome_pct, outcome_r)
                VALUES (:id, (SELECT coalesce(max(seq), -1) + 1 FROM base_status_history WHERE base_id = :id), :status, :date, :trigger, :entry,
                    :confirmed, :exit, :pct, :r)
                """)
                .param("id", b.id()).param("status", b.status().name()).param("date", b.statusDate()).param("trigger", b.triggerDate(), java.sql.Types.DATE)
                .param("entry", b.entry(), java.sql.Types.NUMERIC).param("confirmed", b.volumeConfirmed(), java.sql.Types.BOOLEAN)
                .param("exit", b.exit(), java.sql.Types.NUMERIC).param("pct", b.outcomePct(), java.sql.Types.DOUBLE)
                .param("r", b.outcomeR(), java.sql.Types.DOUBLE).update();
    }

    Optional<LocalDate> progress(UUID instrumentId, String version) {
        return jdbc.sql("SELECT processed_through FROM base_progress WHERE instrument_id = :i AND engine_version = :v")
                .param("i", instrumentId).param("v", version).query(LocalDate.class).optional();
    }

    void progress(UUID instrumentId, String version, LocalDate through) {
        jdbc.sql("""
                INSERT INTO base_progress (instrument_id, engine_version, processed_through) VALUES (:i, :v, :d)
                ON CONFLICT (instrument_id, engine_version) DO UPDATE SET processed_through = EXCLUDED.processed_through
                """).param("i", instrumentId).param("v", version).param("d", through).update();
    }

    /** Every base of the instrument with its current status (open ones carry the lifecycle on, all of them block re-detection). */
    List<Base> ofInstrument(UUID instrumentId, String version) {
        return jdbc.sql(AS_OF + " AND b.instrument_id = :i ORDER BY b.detected_date, b.type").param("asOf", LocalDate.of(9999, 1, 1))
                .param("v", version).param("i", instrumentId).query(this::map).list();
    }

    /** Bases detected on or before {@code asOf} with their status as of that session. */
    List<Base> asOf(LocalDate asOf, String version) {
        return jdbc.sql(AS_OF + " AND b.detected_date <= :asOf ORDER BY b.symbol, b.detected_date, b.type").param("asOf", asOf).param("v", version)
                .query(this::map).list();
    }

    List<Base> ofSymbol(String symbol, LocalDate asOf, String version) {
        return jdbc.sql(AS_OF + " AND b.symbol = :s AND b.detected_date <= :asOf ORDER BY b.detected_date DESC, b.type").param("asOf", asOf)
                .param("v", version).param("s", symbol).query(this::map).list();
    }

    /** Status rows written for {@code date} (the nightly alerts read the day's transitions). */
    List<Base> transitions(LocalDate date, String version) {
        return jdbc.sql(AS_OF + " AND h.status_date = :asOf ORDER BY b.symbol, b.type").param("asOf", date).param("v", version).query(this::map).list();
    }

    private Base map(ResultSet rs, int i) throws SQLException {
        try {
            Boolean confirmed = rs.getBoolean("volume_confirmed");
            if (rs.wasNull()) {
                confirmed = null;
            }
            return new Base(rs.getObject("id", UUID.class), rs.getObject("instrument_id", UUID.class), rs.getString("symbol"),
                    BaseType.valueOf(rs.getString("type")), rs.getString("engine_version"), rs.getObject("start_date", LocalDate.class),
                    rs.getObject("detected_date", LocalDate.class), rs.getDouble("depth_pct"), rs.getBigDecimal("base_low"), rs.getBigDecimal("pivot"),
                    rs.getBigDecimal("buy_low"), rs.getBigDecimal("buy_high"), rs.getBigDecimal("stop"), rs.getBigDecimal("goal"),
                    json.readValue(rs.getString("evidence"), EVIDENCE), BaseStatus.valueOf(rs.getString("status")),
                    rs.getObject("status_date", LocalDate.class), rs.getObject("trigger_date", LocalDate.class), rs.getBigDecimal("entry"), confirmed,
                    rs.getBigDecimal("exit"), rs.getObject("outcome_pct", Double.class), rs.getObject("outcome_r", Double.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
