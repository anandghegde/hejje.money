package money.hejje.ratings;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The watchlist: symbols whose setup alerts fire like a Leader's and whose session analogs are computed at every checkpoint. */
@Service
public class WatchlistService {

    private final JdbcClient jdbc;
    private final InstrumentService instruments;
    private final AuditService audit;
    private final HejjeClock clock;

    WatchlistService(JdbcClient jdbc, InstrumentService instruments, AuditService audit, HejjeClock clock) {
        this.jdbc = jdbc;
        this.instruments = instruments;
        this.audit = audit;
        this.clock = clock;
    }

    public List<WatchlistItem> items() {
        return jdbc.sql("SELECT * FROM watchlist_item ORDER BY symbol")
                .query((rs, i) -> new WatchlistItem(rs.getString("symbol"), rs.getObject("instrument_id", UUID.class), rs.getString("note"),
                        rs.getObject("added_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public List<UUID> instrumentIds() {
        return jdbc.sql("SELECT instrument_id FROM watchlist_item ORDER BY symbol").query(UUID.class).list();
    }

    /** Adds the symbol, or replaces its note. */
    public WatchlistItem add(String symbol, String note, String actor) {
        Instrument instrument = instruments.resolve(symbol == null ? "" : symbol.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Unknown symbol " + symbol));
        String key = instrument.hejjeSymbol().format();
        jdbc.sql("""
                INSERT INTO watchlist_item (symbol, instrument_id, note, added_at) VALUES (:s, :i, :n, :at)
                ON CONFLICT (symbol) DO UPDATE SET note = EXCLUDED.note
                """).param("s", key).param("i", instrument.id()).param("n", note, java.sql.Types.VARCHAR)
                .param("at", clock.now().atOffset(ZoneOffset.UTC)).update();
        audit.record(AuditEvent.of(AuditEventType.WATCHLIST_UPDATED, ActorType.USER).withActorId(actor)
                .withPayload(Map.of("action", "add", "symbol", key, "note", note == null ? "" : note)));
        return items().stream().filter(w -> w.symbol().equals(key)).findFirst().orElseThrow();
    }

    public boolean remove(String symbol, String actor) {
        String key = symbol.trim().toUpperCase();
        boolean removed = jdbc.sql("DELETE FROM watchlist_item WHERE symbol = :s").param("s", key).update() > 0;
        if (removed) {
            audit.record(AuditEvent.of(AuditEventType.WATCHLIST_UPDATED, ActorType.USER).withActorId(actor)
                    .withPayload(Map.of("action", "remove", "symbol", key)));
        }
        return removed;
    }
}
