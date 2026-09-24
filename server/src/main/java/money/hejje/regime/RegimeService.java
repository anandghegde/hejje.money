package money.hejje.regime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.regime.internal.RegimeEngine;
import money.hejje.regime.internal.RegimeStore;
import org.springframework.stereotype.Service;

/**
 * Public API of the regime module: the current snapshot (cached for the intraday snapshot interval), stored final
 * labels for past sessions, and the historical labelling job. Everything degrades to {@code UNKNOWN} labels.
 */
@Service
public class RegimeService {

    private final RegimeEngine engine;
    private final RegimeStore store;
    private final RegimeProperties props;
    private final HejjeClock clock;
    private final org.springframework.context.ApplicationEventPublisher events;
    private volatile RegimeSnapshot cached;

    RegimeService(RegimeEngine engine, RegimeStore store, RegimeProperties props, HejjeClock clock,
            org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.engine = engine;
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public String classifierVersion() {
        return props.classifierVersion();
    }

    /** The regime of the current (or last) session as of now; recomputed when the cached copy is older than the snapshot interval. */
    public RegimeSnapshot current() {
        Instant now = clock.now();
        RegimeSnapshot c = cached;
        if (c != null && c.date().equals(sessionDate(now)) && !c.asOf().plus(props.intradaySnapshot()).isBefore(now)) {
            return c;
        }
        return snapshotNow(false);
    }

    /** Computes the snapshot for the current session as of now; optionally stores it (intraday row, or the final row once the session closed). */
    public RegimeSnapshot snapshotNow(boolean persist) {
        Instant now = clock.now();
        LocalDate date = sessionDate(now);
        if (!props.enabled()) {
            return RegimeSnapshot.unknown(date, now, props.classifierVersion(), "regime engine disabled (hejje.regime.enabled=false)");
        }
        RegimeSnapshot snapshot;
        try {
            snapshot = engine.snapshot(date, now);
        } catch (RuntimeException e) {
            snapshot = RegimeSnapshot.unknown(date, now, props.classifierVersion(), "regime inputs unavailable: " + e.getMessage());
        }
        cached = snapshot;
        if (persist && !snapshot.isUnknown()) {
            if (snapshot.finalLabel()) {
                announceConditionChange(snapshot);
                store.upsertFinal(snapshot, now);
            } else {
                store.insertIntraday(snapshot, now);
            }
        }
        return snapshot;
    }

    /** Tells clients and the notification rules when the session's market condition differs from the previous session's. */
    private void announceConditionChange(RegimeSnapshot snapshot) {
        List<RegimeSnapshot> earlier = store.finals(snapshot.date().minusDays(10), snapshot.date().minusDays(1), props.classifierVersion());
        if (earlier.isEmpty()) {
            return;
        }
        MarketCondition previous = earlier.get(earlier.size() - 1).marketCondition();
        MarketCondition current = snapshot.marketCondition();
        if (previous == current || previous == MarketCondition.UNKNOWN || current == MarketCondition.UNKNOWN) {
            return;
        }
        String sentence = snapshot.evidence().stream().filter(e -> e.startsWith("Market condition")).findFirst().orElse("");
        events.publishEvent(new money.hejje.common.ClientNotification("market_condition", java.util.Map.of("date", snapshot.date().toString(),
                "previous", previous.name(), "current", current.name(), "evidence", sentence)));
    }

    /** The stored final label of a session under the current classifier version. */
    public Optional<RegimeSnapshot> forDate(LocalDate date) {
        return store.findFinal(date, props.classifierVersion());
    }

    public List<RegimeSnapshot> history(LocalDate from, LocalDate to) {
        return store.finals(from, to, props.classifierVersion());
    }

    /** Final labels keyed by session for {@code [from, to]}; sessions without a label are absent. */
    public Map<LocalDate, RegimeSnapshot> labels(LocalDate from, LocalDate to) {
        Map<LocalDate, RegimeSnapshot> out = new TreeMap<>();
        for (RegimeSnapshot s : history(from, to)) {
            out.put(s.date(), s);
        }
        return out;
    }

    public List<RegimeSnapshot> intradaySnapshots(LocalDate date) {
        return store.intraday(date, props.classifierVersion());
    }

    /**
     * Labels every trading session in {@code [from, to]} that has an index daily bar (final rows, upserted). Sessions
     * are walked in order so each label only sees data up to its own session; the result hash is stable across runs.
     */
    public RegimeLabelResult labelHistory(LocalDate from, LocalDate to) {
        Instant now = clock.now();
        int expected = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (clock.isTradingDay(d)) {
                expected++;
            }
        }
        if (!props.enabled()) {
            return new RegimeLabelResult(from, to, props.classifierVersion(), expected, 0, hash(List.of()));
        }
        List<RegimeSnapshot> labelled = engine.labelRange(from, to);
        List<String> lines = new ArrayList<>();
        for (RegimeSnapshot s : labelled) {
            store.upsertFinal(s, now);
            lines.add(s.date() + "|" + s.trend() + "|" + s.volatility() + "|" + s.opening() + "|" + s.breadth() + "|" + s.intradayStructure() + "|"
                    + s.eventEnvironment() + "|" + s.marketCondition());
        }
        return new RegimeLabelResult(from, to, props.classifierVersion(), expected, labelled.size(), hash(lines));
    }

    /** Sessions in {@code [from, to]} still missing a final label under the current classifier version. */
    public int missingLabels(LocalDate from, LocalDate to) {
        int expected = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (clock.isTradingDay(d)) {
                expected++;
            }
        }
        return Math.max(0, expected - store.countFinals(from, to, props.classifierVersion()));
    }

    /** The session "now" belongs to: today when it is a trading day, else the previous trading day. */
    public LocalDate sessionDate(Instant now) {
        ZoneId zone = clock.zone();
        LocalDate d = now.atZone(zone).toLocalDate();
        while (!clock.isTradingDay(d)) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** Daily bars of the index instrument (for callers that size the history range), empty when the index is unknown. */
    public List<Candle> indexDaily(LocalDate from, LocalDate to) {
        return engine.indexDaily(from, to);
    }

    public Optional<UUID> indexInstrumentId() {
        return engine.indexId();
    }

    static String hash(List<String> lines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Convenience for UIs: the labels as a display map. */
    public static Map<String, String> labelsOf(RegimeSnapshot s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("trend", s.trend().name());
        m.put("volatility", s.volatility().name());
        m.put("opening", s.opening().name());
        m.put("breadth", s.breadth().name());
        m.put("intradayStructure", s.intradayStructure().name());
        m.put("eventEnvironment", s.eventEnvironment().name());
        return m;
    }
}
