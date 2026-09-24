package money.hejje.calibration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import money.hejje.calibration.internal.CalibrationMath;
import money.hejje.calibration.internal.CalibrationMath.Point;
import money.hejje.calibration.internal.CalibrationStore;
import money.hejje.calibration.internal.Labeler;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Records predictions, labels them from stored candles and reports calibration (plan M9.2, docs/calibration.md). A
 * prediction is labelled once its window has passed on the Hejje clock (simulation time in SIM); a label never changes,
 * so adding candles later cannot move a report. {@link #passes} is the pre-registered bar that gates on Jev numbers check.
 */
@Service
public class CalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    private final CalibrationStore store;
    private final MarketService market;
    private final HejjeClock clock;
    private final CalibrationProperties props;

    CalibrationService(CalibrationStore store, MarketService market, HejjeClock clock, CalibrationProperties props) {
        this.store = store;
        this.market = market;
        this.clock = clock;
        this.props = props;
    }

    /** Records a prediction for labelling; recording it again is a no-op. Never throws on a caller's path. */
    public void record(Prediction p) {
        try {
            store.insert(p, horizon(p.rule()), p.decidedAt().atZone(clock.zone()).toLocalDate());
        } catch (RuntimeException e) {
            log.warn("Calibration record of {} {} failed", p.purpose(), p.sourceId(), e);
        }
    }

    /** Labels every pending prediction whose window has passed; returns how many were labelled. */
    public int labelDue() {
        Instant now = clock.now();
        int n = 0;
        for (CalibrationStore.Row r : store.pending(now, 50_000)) {
            try {
                if (labelOne(r, now)) {
                    n++;
                }
            } catch (RuntimeException e) {
                log.warn("Labelling {} {} failed", r.prediction().purpose(), r.prediction().sourceId(), e);
            }
        }
        return n;
    }

    private boolean labelOne(CalibrationStore.Row r, Instant now) {
        Prediction p = r.prediction();
        Instant end = windowEnd(p, r.sessionDate());
        if (end.isAfter(now)) {
            return false;
        }
        Labeler.Label label;
        if (p.rule() == LabelRule.DIRECTION_NEXT_CLOSE) {
            label = nextClose(p, end);
        } else {
            List<Candle> bars = market.candles(p.instrumentId(), Timeframe.M1, p.decidedAt(), end).stream()
                    .filter(c -> !c.openTime().isBefore(p.decidedAt()) && c.openTime().isBefore(end)).toList();
            label = switch (p.rule()) {
                case ENTRY_1R -> Labeler.entry(p, bars);
                case DIRECTION -> Labeler.direction(p, bars);
                case EXIT -> Labeler.exit(p, bars);
                case DIRECTION_NEXT_CLOSE -> throw new IllegalStateException();
            };
        }
        if (label == null) {
            if (now.isBefore(end.plus(Duration.ofDays(props.noDataAfterDays())))) {
                return false; // candles may still arrive (backfill)
            }
            label = new Labeler.Label(Labeler.Outcome.NONE, Map.of("reason", "no candles in the window"));
        }
        Map<String, Object> evidence = new LinkedHashMap<>(label.evidence());
        evidence.put("windowEnd", end.toString());
        return store.label(r, label.outcome().name(), evidence, now);
    }

    /** News: from the first price after the decision to the close of that price's session (M1 when stored, else D1). */
    private Labeler.Label nextClose(Prediction p, Instant end) {
        List<Candle> m1 = market.candles(p.instrumentId(), Timeframe.M1, p.decidedAt(), end).stream()
                .filter(c -> !c.openTime().isBefore(p.decidedAt()) && c.openTime().isBefore(end)).toList();
        if (!m1.isEmpty()) {
            return Labeler.direction(p, m1);
        }
        LocalDate day = end.atZone(clock.zone()).toLocalDate();
        Instant dayStart = day.atStartOfDay(clock.zone()).toInstant();
        List<Candle> d1 = market.candles(p.instrumentId(), Timeframe.D1, dayStart, end).stream()
                .filter(c -> c.openTime().atZone(clock.zone()).toLocalDate().equals(day)).toList();
        return d1.isEmpty() ? null : Labeler.direction(p, d1);
    }

    /** The end of a prediction's window: its horizon, cut at the session close; news runs to the next close. */
    Instant windowEnd(Prediction p, LocalDate date) {
        ZonedDateTime close = date.atTime(HejjeClock.SESSION_CLOSE).atZone(clock.zone());
        if (p.rule() == LabelRule.DIRECTION_NEXT_CLOSE) {
            boolean before = clock.isTradingDay(date) && p.decidedAt().isBefore(close.toInstant());
            return before ? close.toInstant() : clock.nextTradingDay(date).atTime(HejjeClock.SESSION_CLOSE).atZone(clock.zone()).toInstant();
        }
        Instant horizonEnd = p.decidedAt().plus(Duration.ofMinutes(minutes(p.rule())));
        return horizonEnd.isBefore(close.toInstant()) ? horizonEnd : close.toInstant();
    }

    private int minutes(LabelRule rule) {
        return switch (rule) {
            case ENTRY_1R -> props.entryHorizonMinutes();
            case DIRECTION -> props.directionHorizonMinutes();
            case EXIT -> props.exitHorizonMinutes();
            case DIRECTION_NEXT_CLOSE -> 0;
        };
    }

    private String horizon(LabelRule rule) {
        return rule == LabelRule.DIRECTION_NEXT_CLOSE ? "next_close" : minutes(rule) + "m";
    }

    /** The calibration report of a purpose and version (the newest version when none is given) over sessions in {@code [from, to]}. */
    public CalibrationReport report(String purpose, String version, LocalDate from, LocalDate to) {
        if (version == null) { // never pooled across versions: the newest one
            version = store.purposes().stream().filter(r -> r.purpose().equals(purpose)).max(java.util.Comparator.comparing(CalibrationStore.PurposeRow::last))
                    .map(CalibrationStore.PurposeRow::version).orElse(null);
        }
        List<CalibrationStore.Point> rows = store.points(purpose, version, from, to);
        List<Point> labelled = new ArrayList<>();
        int none = 0;
        int pending = 0;
        java.util.Set<LocalDate> sessions = new java.util.HashSet<>();
        for (CalibrationStore.Point r : rows) {
            switch (r.outcome()) {
                case "HIT", "MISS" -> {
                    labelled.add(new Point(r.probability(), "HIT".equals(r.outcome())));
                    sessions.add(r.sessionDate());
                }
                case "NONE" -> none++;
                default -> pending++;
            }
        }
        return report(purpose, version, from, to, labelled, none, pending, sessions.size(), props);
    }

    /** The report over given labelled points, with the pass bar applied (pure; the tests use it on synthetic sources). */
    static CalibrationReport report(String purpose, String version, LocalDate from, LocalDate to, List<Point> labelled, int none, int pending,
            int sessions, CalibrationProperties props) {
        List<CalibrationReport.Bucket> buckets = CalibrationMath.buckets(labelled, props.minBucketCount());
        Double ece = CalibrationMath.ece(labelled);
        CalibrationReport.TopVsBottom tb = CalibrationMath.topVsBottom(buckets);
        List<String> reasons = new ArrayList<>();
        if (labelled.size() < props.minLabelled()) {
            reasons.add(labelled.size() + " labelled answers, the bar is " + props.minLabelled());
        }
        if (sessions < props.minSessions()) {
            reasons.add(sessions + " sessions, the bar is " + props.minSessions());
        }
        if (ece == null || ece > props.maxEce()) {
            reasons.add("expected calibration error " + (ece == null ? "n/a" : ece) + ", the bar is " + props.maxEce());
        }
        if (tb == null || !tb.separated()) {
            reasons.add("the top populated bucket's Wilson lower bound is not above the bottom one's upper bound");
        }
        return new CalibrationReport(purpose, version, from, to, labelled.size(), none, pending, sessions, buckets, CalibrationMath.brier(labelled), ece, tb,
                reasons.isEmpty(), reasons);
    }

    /** The pre-registered bar (docs/calibration.md) over everything recorded for the purpose and version. */
    public boolean passes(String purpose, String version) {
        return report(purpose, version, null, null).passes();
    }

    /** A purpose and version with its counts. */
    public record Purpose(String purpose, String version, int predictions, int labelled, LocalDate first, LocalDate last) {}

    public List<Purpose> purposes() {
        return store.purposes().stream().map(r -> new Purpose(r.purpose(), r.version(), r.predictions(), r.labelled(), r.first(), r.last()))
                .collect(Collectors.toList());
    }

    /** Buckets, Brier and counts for a SIM report: {@code n}, {@code none}, {@code pending}, {@code brier}, {@code buckets}. */
    public Map<String, Object> summary(String purpose, String version, LocalDate from, LocalDate to) {
        CalibrationReport r = report(purpose, version, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", r.n());
        out.put("none", r.none());
        out.put("pending", r.pending());
        out.put("brier", r.brier());
        out.put("buckets", r.buckets().stream().filter(b -> b.n() > 0).map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lo", b.lo());
            m.put("hi", b.hi());
            m.put("n", b.n());
            m.put("hits", b.hits());
            return m;
        }).toList());
        return out;
    }

    /** The purpose under which a bot's entry confidence is calibrated. */
    public static String botPurpose(String botName) {
        return "bot:" + botName;
    }
}
