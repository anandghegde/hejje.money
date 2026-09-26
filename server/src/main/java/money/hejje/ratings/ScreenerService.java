package money.hejje.ratings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The screener (plan M8.7): one flat row of fields per stock for a session (its ratings, its open base, and whatever
 * other modules contribute through {@link ScreenFieldSource}), filtered by field / operator / value and sorted. Saved
 * screens are stored requests. The field list is fixed and documented (docs/ratings.md, "Screener").
 */
@Service
public class ScreenerService {

    /** Fields of the ratings module, in display order. */
    public static final List<String> FIELDS = List.of("symbol", "close", "changePct", "rsRating", "rsRaw", "adGrade", "adRaw", "techComposite",
            "offHighPct", "offLowPct", "volVsAvg50Pct", "upDownVolRatio", "avgTurnoverCr", "groupId", "groupRank", "baseType", "baseStatus", "pivot",
            "distanceToPivotPct", "volumeConfirmed", "watchlist", "surveillance");
    private static final List<String> OPS = List.of("gte", "lte", "gt", "lt", "eq", "ne", "in");

    private final RatingsService ratings;
    private final WatchlistService watchlist;
    private final List<ScreenFieldSource> sources;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final HejjeClock clock;

    ScreenerService(RatingsService ratings, WatchlistService watchlist, List<ScreenFieldSource> sources, JdbcClient jdbc, ObjectMapper json,
            AuditService audit, HejjeClock clock) {
        this.ratings = ratings;
        this.watchlist = watchlist;
        this.sources = sources;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    /** Every field a filter or a sort may name. */
    public List<String> fields() {
        List<String> all = new ArrayList<>(FIELDS);
        sources.forEach(s -> all.addAll(s.fields()));
        return all;
    }

    /** The result of a screen: the session it ran on, how many stocks were screened, and the matching rows. */
    public record Result(String date, int universe, int matched, List<Map<String, Object>> rows) {
    }

    public Result run(ScreenRequest request) {
        validate(request);
        Optional<LocalDate> session = ratings.sessionFor(request.date());
        if (session.isEmpty()) {
            return new Result("", 0, 0, List.of());
        }
        List<Map<String, Object>> rows = rows(session.get());
        List<Map<String, Object>> matched = rows.stream()
                .filter(row -> request.filters() == null || request.filters().stream().allMatch(f -> matches(row.get(f.field()), f))).toList();
        String sort = request.sort() == null || request.sort().isBlank() ? "-techComposite" : request.sort();
        boolean descending = sort.startsWith("-");
        String field = descending ? sort.substring(1) : sort;
        Comparator<Map<String, Object>> byField = Comparator.comparing(row -> row.get(field), Comparator.nullsLast(ScreenerService::compare));
        if (descending) {
            byField = Comparator.comparing((Map<String, Object> row) -> row.get(field), Comparator.nullsLast(((Comparator<Object>) ScreenerService::compare).reversed()));
        }
        int limit = request.limit() == null ? 50 : Math.max(1, Math.min(request.limit(), 1000));
        return new Result(session.get().toString(), rows.size(), matched.size(),
                matched.stream().sorted(byField.thenComparing(row -> (String) row.get("symbol"))).limit(limit).toList());
    }

    /** One flat row per rated stock of the session. */
    public List<Map<String, Object>> rows(LocalDate session) {
        Map<UUID, Base> openBase = new LinkedHashMap<>();
        for (Base b : ratings.bases(null, null, session)) {
            // one open base per stock is listed: a base before a reversal setup, the newest first
            if (!b.status().closed() && (!openBase.containsKey(b.instrumentId()) || openBase.get(b.instrumentId()).type().reversal())) {
                openBase.put(b.instrumentId(), b);
            }
        }
        Collection<UUID> watched = watchlist.instrumentIds();
        List<Map<String, Map<String, Object>>> contributed = sources.stream().map(s -> s.values(session)).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DailyRating r : ratings.ratings(session)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", r.symbol());
            row.put("close", r.close());
            row.put("changePct", r.changePct());
            row.put("rsRating", r.rsRating());
            row.put("rsRaw", r.rsRaw());
            row.put("adGrade", r.adGrade());
            row.put("adRaw", r.adRaw());
            row.put("techComposite", r.techComposite());
            row.put("offHighPct", r.offHighPct());
            row.put("offLowPct", r.offLowPct());
            row.put("volVsAvg50Pct", r.volVsAvg50Pct());
            row.put("upDownVolRatio", r.upDownVolRatio());
            row.put("avgTurnoverCr", r.avgTurnoverCr());
            row.put("groupId", r.groupId());
            row.put("groupRank", r.groupRank());
            Base b = openBase.get(r.instrumentId());
            row.put("baseType", b == null ? null : b.type().name());
            row.put("baseStatus", b == null ? null : b.status().name());
            row.put("pivot", b == null ? null : b.pivot());
            row.put("distanceToPivotPct", b == null ? null
                    : Math.round((r.close().doubleValue() / b.pivot().doubleValue() - 1.0) * 10000.0) / 100.0);
            row.put("volumeConfirmed", b == null ? null : b.volumeConfirmed());
            row.put("watchlist", watched.contains(r.instrumentId()));
            row.put("surveillance", r.surveillance() == null ? null : r.surveillance().flag());
            contributed.forEach(values -> row.putAll(values.getOrDefault(r.symbol(), Map.of())));
            rows.add(row);
        }
        return rows;
    }

    private void validate(ScreenRequest request) {
        List<String> known = fields();
        if (request.filters() != null) {
            for (ScreenRequest.Filter f : request.filters()) {
                if (f.field() == null || !known.contains(f.field())) {
                    throw new IllegalArgumentException("Unknown screen field " + f.field() + "; fields: " + known);
                }
                if (f.op() == null || !OPS.contains(f.op())) {
                    throw new IllegalArgumentException("Unknown operator " + f.op() + "; operators: " + OPS);
                }
                if (f.value() == null || (f.op().equals("in") != (f.value() instanceof Collection<?>))) {
                    throw new IllegalArgumentException("Filter on " + f.field() + " needs a value (a list for in)");
                }
            }
        }
        if (request.sort() != null && !request.sort().isBlank() && !known.contains(request.sort().replaceFirst("^-", ""))) {
            throw new IllegalArgumentException("Unknown sort field " + request.sort());
        }
    }

    /** A missing value never matches, whatever the operator. */
    private static boolean matches(Object actual, ScreenRequest.Filter f) {
        if (actual == null) {
            return false;
        }
        if (f.op().equals("in")) {
            return ((Collection<?>) f.value()).stream().anyMatch(v -> compare(actual, v) == 0);
        }
        int c = compare(actual, f.value());
        return switch (f.op()) {
            case "gte" -> c >= 0;
            case "lte" -> c <= 0;
            case "gt" -> c > 0;
            case "lt" -> c < 0;
            case "eq" -> c == 0;
            default -> c != 0;
        };
    }

    /** Numbers compare as numbers (a numeric string counts), everything else as case-insensitive text. */
    private static int compare(Object a, Object b) {
        Double x = number(a);
        Double y = number(b);
        if (x != null && y != null) {
            return Double.compare(x, y);
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof BigDecimal d) {
            return d.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // --- saved screens ---

    public List<SavedScreen> screens() {
        return jdbc.sql("SELECT * FROM screen ORDER BY seeded DESC, name").query((rs, i) -> {
            try {
                return new SavedScreen(rs.getObject("id", UUID.class), rs.getString("name"), json.readValue(rs.getString("definition"), ScreenRequest.class),
                        rs.getBoolean("seeded"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }).list();
    }

    public Optional<SavedScreen> screen(UUID id) {
        return screens().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** Creates the screen, or replaces the definition of the screen with that name. The date of the request is not stored. */
    public SavedScreen save(String name, ScreenRequest definition, String actor) {
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new IllegalArgumentException("A screen needs a name of at most 80 characters");
        }
        ScreenRequest stored = new ScreenRequest(null, definition.filters() == null ? List.of() : definition.filters(), definition.sort(), definition.limit());
        validate(stored);
        try {
            jdbc.sql("""
                    INSERT INTO screen (id, name, definition, seeded, created_at, updated_at) VALUES (:id, :name, CAST(:definition AS jsonb), FALSE, :now, :now)
                    ON CONFLICT (name) DO UPDATE SET definition = EXCLUDED.definition, updated_at = EXCLUDED.updated_at
                    """).param("id", Ids.newId()).param("name", name.trim()).param("definition", json.writeValueAsString(stored))
                    .param("now", clock.now().atOffset(ZoneOffset.UTC)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        audit.record(AuditEvent.of(AuditEventType.SCREEN_SAVED, ActorType.USER).withActorId(actor).withPayload(Map.of("name", name.trim())));
        return screens().stream().filter(s -> s.name().equals(name.trim())).findFirst().orElseThrow();
    }

    public boolean delete(UUID id, String actor) {
        Optional<SavedScreen> existing = screen(id);
        if (existing.isEmpty()) {
            return false;
        }
        jdbc.sql("DELETE FROM screen WHERE id = :id").param("id", id).update();
        audit.record(AuditEvent.of(AuditEventType.SCREEN_DELETED, ActorType.USER).withActorId(actor).withPayload(Map.of("name", existing.get().name())));
        return true;
    }
}
