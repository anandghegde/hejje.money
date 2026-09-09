package money.hejje.events.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.events.EventScope;
import money.hejje.events.EventType;
import money.hejje.events.MarketEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code market_event}; upserts by {@code (source, external_key)} so refreshes never duplicate. */
@Repository
public class EventStore {

    private static final TypeReference<Map<String, Object>> RAW = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    EventStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Inserts or updates; returns true when a row was inserted. */
    public boolean upsert(MarketEvent e) {
        try {
            int inserted = jdbc.sql("""
                    INSERT INTO market_event (id, type, scope, instrument_id, symbol, title, starts_at, ends_at, all_day, source, external_key, confidence, raw, imported_at)
                    VALUES (:id, :type, :scope, :instrumentId, :symbol, :title, :startsAt, :endsAt, :allDay, :source, :key, :confidence, CAST(:raw AS jsonb), :importedAt)
                    ON CONFLICT (source, external_key) DO UPDATE SET type = EXCLUDED.type, scope = EXCLUDED.scope, instrument_id = EXCLUDED.instrument_id,
                        symbol = EXCLUDED.symbol, title = EXCLUDED.title, starts_at = EXCLUDED.starts_at, ends_at = EXCLUDED.ends_at, all_day = EXCLUDED.all_day,
                        confidence = EXCLUDED.confidence, raw = EXCLUDED.raw, imported_at = EXCLUDED.imported_at
                    RETURNING (xmax = 0) AS inserted
                    """)
                    .param("id", e.id()).param("type", e.type().name()).param("scope", e.scope().name()).param("instrumentId", e.instrumentId(), java.sql.Types.OTHER)
                    .param("symbol", e.symbol()).param("title", e.title()).param("startsAt", e.startsAt().atOffset(ZoneOffset.UTC))
                    .param("endsAt", e.endsAt() == null ? null : e.endsAt().atOffset(ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("allDay", e.allDay()).param("source", e.source()).param("key", e.externalKey()).param("confidence", e.confidence())
                    .param("raw", json.writeValueAsString(e.raw())).param("importedAt", e.importedAt().atOffset(ZoneOffset.UTC))
                    .query(Boolean.class).single() ? 1 : 0;
            return inserted == 1;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /** Events overlapping {@code [from, to)}; {@code instrumentId} null = market events only, else market events plus the instrument's. */
    public List<MarketEvent> find(Instant from, Instant to, UUID instrumentId, String symbol) {
        String scopeClause = instrumentId == null && symbol == null ? "scope = 'MARKET'"
                : "(scope = 'MARKET' OR instrument_id = :instrumentId OR (:symbol IS NOT NULL AND symbol = :symbol))";
        var spec = jdbc.sql("SELECT * FROM market_event WHERE starts_at < :to AND COALESCE(ends_at, starts_at) >= :from AND " + scopeClause
                + " ORDER BY starts_at, title")
                .param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC));
        if (instrumentId != null || symbol != null) {
            spec = spec.param("instrumentId", instrumentId, java.sql.Types.OTHER).param("symbol", symbol, java.sql.Types.VARCHAR);
        }
        return spec.query(this::map).list();
    }

    /** All events (any scope) overlapping {@code [from, to)}. */
    public List<MarketEvent> findAll(Instant from, Instant to) {
        return jdbc.sql("SELECT * FROM market_event WHERE starts_at < :to AND COALESCE(ends_at, starts_at) >= :from ORDER BY starts_at, title")
                .param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC)).query(this::map).list();
    }

    private MarketEvent map(ResultSet rs, int i) throws SQLException {
        try {
            Object instrument = rs.getObject("instrument_id");
            OffsetDateTime ends = rs.getObject("ends_at", OffsetDateTime.class);
            return new MarketEvent(rs.getObject("id", UUID.class), EventType.valueOf(rs.getString("type")), EventScope.valueOf(rs.getString("scope")),
                    instrument == null ? null : (UUID) instrument, rs.getString("symbol"), rs.getString("title"),
                    rs.getObject("starts_at", OffsetDateTime.class).toInstant(), ends == null ? null : ends.toInstant(), rs.getBoolean("all_day"),
                    rs.getString("source"), rs.getString("external_key"), rs.getDouble("confidence"), json.readValue(rs.getString("raw"), RAW),
                    rs.getObject("imported_at", OffsetDateTime.class).toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
