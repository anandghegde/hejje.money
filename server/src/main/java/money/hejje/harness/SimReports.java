package money.hejje.harness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import money.hejje.bots.Bot;
import money.hejje.bots.BotDecision;
import money.hejje.bots.BotDecisions;
import money.hejje.bots.BotService;
import money.hejje.calibration.CalibrationService;
import money.hejje.harness.internal.SimReportStore;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionFinished;
import money.hejje.sim.SimSessionService;
import money.hejje.strategy.BotPromotionEvidence;
import money.hejje.strategy.StrategyVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Session reports, the leaderboard and bot promotion (plan M7.5). When a SIM session finishes DONE, every bot listed in
 * it gets a report built from its harness snapshot. The leaderboard ranks bots (and strategy bots) by expectancy net of
 * costs over their reports in a date range, with profit factor, max drawdown and trade count alongside. A bot's backing
 * strategy may be deployed in PAPER after {@code hejje.bots.min-sim-sessions} (20) reports with positive expectancy over
 * all their trades. Reports are keyed by the bot's name and version, so a SIM instance's reports can be exported and
 * imported where the bot is promoted. Before the reports are built, the session's predictions are labelled (plan M9.2),
 * so each report carries the bot's confidence calibration and the leaderboard its pooled Brier score.
 */
@Service
public class SimReports implements BotPromotionEvidence {

    private static final Logger log = LoggerFactory.getLogger(SimReports.class);

    private final SimReportStore store;
    private final HarnessService harness;
    private final ObjectProvider<SimSessionService> sessions;
    private final BotService bots;
    private final BotDecisions decisions;
    private final CalibrationService calibration;
    private final int minSimSessions;
    /** Report bookkeeping is wall time. */
    private final Clock wall = Clock.systemUTC();

    SimReports(SimReportStore store, HarnessService harness, ObjectProvider<SimSessionService> sessions, BotService bots, BotDecisions decisions,
            CalibrationService calibration, @Value("${hejje.bots.min-sim-sessions:20}") int minSimSessions) {
        this.store = store;
        this.harness = harness;
        this.sessions = sessions;
        this.bots = bots;
        this.decisions = decisions;
        this.calibration = calibration;
        this.minSimSessions = minSimSessions;
    }

    public int minSimSessions() {
        return minSimSessions;
    }

    @EventListener
    void onFinished(SimSessionFinished event) {
        if (event.state() != SimSession.State.DONE) {
            return;
        }
        SimSessionService service = sessions.getIfAvailable();
        SimSession session = service == null ? null : service.find(event.sessionId()).orElse(null);
        if (session == null) {
            return;
        }
        calibration.labelDue(); // SIM labels at the end of the session, from the replayed candles
        for (Map<String, Object> entry : session.spec().bots()) {
            try {
                bots.find(UUID.fromString(String.valueOf(entry.get("botId")))).ifPresent(bot -> store.insert(report(session, bot)));
            } catch (RuntimeException e) {
                log.warn("Report of bot {} in session {} failed", entry.get("botId"), session.id(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    SimReport report(SimSession session, Bot bot) {
        Map<String, Object> snapshot = harness.snapshot(bot.id(), session.id());
        Map<String, Object> tiles = (Map<String, Object>) snapshot.get("tiles");
        List<Map<String, Object>> trades = (List<Map<String, Object>>) snapshot.get("trades");
        List<Double> rs = new ArrayList<>();
        long win = 0;
        long loss = 0;
        int wins = 0;
        for (Map<String, Object> t : trades) {
            long pnl = paise(t.get("pnl"));
            if (pnl > 0) {
                wins++;
                win += pnl;
            } else {
                loss += pnl;
            }
            if (t.get("r") instanceof Number r) {
                rs.add(r.doubleValue());
            }
        }
        List<LocalDate> dates = session.spec().dates().isEmpty() ? List.of(session.sessionDate()) : session.spec().dates().stream().sorted().toList();
        return new SimReport(UUID.randomUUID(), session.id(), bot.name(), bot.version(), bot.kind().name(), dates, paise(tiles.get("capital")), trades.size(),
                wins, number(tiles.get("expectancyR")), number(tiles.get("profitFactor")), paise(tiles.get("maxDrawdown")), paise(tiles.get("totalPnl")), win,
                loss, paise(tiles.get("frictionPaid")), rs, decisionsHash(bot, dates), session.resultHash(), snapshot,
                calibration.summary(CalibrationService.botPurpose(bot.name()), bot.version(), dates.get(0), dates.get(dates.size() - 1)), wall.instant());
    }

    /** SHA-256 over the bot's decisions on the session's days (point, instrument, action, stop, target, outcome). */
    private String decisionsHash(Bot bot, List<LocalDate> dates) {
        Set<String> days = dates.stream().map(LocalDate::toString).collect(Collectors.toSet());
        String text = decisions.recent(bot.id(), 500).stream()
                .filter(d -> days.contains(d.decidedAt().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().toString()))
                .sorted(Comparator.comparing(BotDecision::pointId).thenComparing(BotDecision::instrument))
                .map(d -> String.join("|", d.pointId(), d.instrument(), d.action().name(), String.valueOf(d.stop()), String.valueOf(d.target()), d.outcome().name()))
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<SimReport> list(String botName, String version, int limit) {
        return store.list(blank(botName), blank(version), Math.max(1, Math.min(limit, 1000)));
    }

    public Optional<SimReport> find(UUID id) {
        return store.find(id);
    }

    /** Imports reports exported from a SIM instance; returns how many were new. */
    public int importReports(List<SimReport> reports) {
        int n = 0;
        for (SimReport r : reports) {
            if (store.insert(r)) {
                n++;
            }
        }
        return n;
    }

    /**
     * One leaderboard row: a bot version's reports in the range, aggregated. {@code brier} is the bot's confidence Brier
     * score pooled over its labelled entries (plan M9.2), null without any.
     */
    public record Row(int rank, String bot, String version, String kind, int sessions, int trades, Double winRate, Double expectancyR, Double profitFactor,
            long maxDrawdownPaise, long netPnlPaise, long frictionPaise, Double brier, int brierN) {}

    /**
     * Bots ranked by expectancy net of costs over their reports whose sessions fall in {@code [from, to]}. With
     * {@code common}, only the session day sets every ranked bot played count, so all rows compare the same sessions.
     */
    public List<Row> leaderboard(LocalDate from, LocalDate to, boolean common) {
        List<SimReport> inRange = store.list(null, null, 10_000).stream()
                .filter(r -> r.sessionDates().stream().allMatch(d -> (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to)))).toList();
        Map<String, List<SimReport>> byBot = new LinkedHashMap<>();
        for (SimReport r : inRange) {
            byBot.computeIfAbsent(r.botName() + " " + r.botVersion(), k -> new ArrayList<>()).add(r);
        }
        if (common && byBot.size() > 1) {
            Set<List<LocalDate>> shared = null;
            for (List<SimReport> reports : byBot.values()) {
                Set<List<LocalDate>> mine = reports.stream().map(SimReport::sessionDates).collect(Collectors.toSet());
                if (shared == null) {
                    shared = mine;
                } else {
                    shared.retainAll(mine);
                }
            }
            Set<List<LocalDate>> keep = shared;
            byBot.replaceAll((k, reports) -> reports.stream().filter(r -> keep.contains(r.sessionDates())).toList());
        }
        List<Row> rows = new ArrayList<>();
        for (List<SimReport> reports : byBot.values()) {
            if (reports.isEmpty()) {
                continue;
            }
            SimReport any = reports.get(0);
            List<Double> rs = reports.stream().flatMap(r -> r.tradeRs().stream()).toList();
            int trades = reports.stream().mapToInt(SimReport::trades).sum();
            int wins = reports.stream().mapToInt(SimReport::wins).sum();
            long win = reports.stream().mapToLong(SimReport::winPaise).sum();
            long loss = reports.stream().mapToLong(SimReport::lossPaise).sum();
            rows.add(new Row(0, any.botName(), any.botVersion(), any.botKind(), reports.size(), trades, trades == 0 ? null : round((double) wins / trades),
                    rs.isEmpty() ? null : round(rs.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
                    loss == 0 ? null : round((double) win / Math.abs(loss)), reports.stream().mapToLong(SimReport::maxDrawdownPaise).max().orElse(0),
                    reports.stream().mapToLong(SimReport::netPnlPaise).sum(), reports.stream().mapToLong(SimReport::frictionPaise).sum(), pooledBrier(reports),
                    brierCount(reports)));
        }
        rows.sort(Comparator.comparing((Row r) -> r.expectancyR() == null ? Double.NEGATIVE_INFINITY : r.expectancyR()).reversed()
                .thenComparing(r -> r.profitFactor() == null ? 0 : r.profitFactor(), Comparator.reverseOrder()));
        List<Row> ranked = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            ranked.add(new Row(i + 1, r.bot(), r.version(), r.kind(), r.sessions(), r.trades(), r.winRate(), r.expectancyR(), r.profitFactor(),
                    r.maxDrawdownPaise(), r.netPnlPaise(), r.frictionPaise(), r.brier(), r.brierN()));
        }
        return ranked;
    }

    /** Σ brier × n / Σ n over the reports' confidence calibrations. */
    static Double pooledBrier(List<SimReport> reports) {
        double sum = 0;
        int n = brierCount(reports);
        for (SimReport r : reports) {
            Map<String, Object> c = r.confidenceCalibration();
            if (c != null && c.get("brier") instanceof Number b && c.get("n") instanceof Number k) {
                sum += b.doubleValue() * k.intValue();
            }
        }
        return n == 0 ? null : round(sum / n);
    }

    static int brierCount(List<SimReport> reports) {
        int n = 0;
        for (SimReport r : reports) {
            Map<String, Object> c = r.confidenceCalibration();
            if (c != null && c.get("brier") instanceof Number && c.get("n") instanceof Number k) {
                n += k.intValue();
            }
        }
        return n;
    }

    /** Plan M7.5: PAPER after {@code minSimSessions} SIM sessions with positive expectancy over all their trades. */
    @Override
    public String refusePaper(StrategyVersion version) {
        Optional<Bot> bot = bots.byStrategy(version.strategyId());
        if (bot.isEmpty()) {
            return null; // not a registered bot's strategy
        }
        return refusal(bot.get().name(), bot.get().version(), list(bot.get().name(), bot.get().version(), 10_000));
    }

    String refusal(String name, String version, List<SimReport> reports) {
        if (reports.size() < minSimSessions) {
            return "bot " + name + " v" + version + " has " + reports.size() + " of the " + minSimSessions
                    + " SIM sessions it needs before PAPER (hejje.bots.min-sim-sessions)";
        }
        List<Double> rs = reports.stream().flatMap(r -> r.tradeRs().stream()).toList();
        double expectancy = rs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (rs.isEmpty() || expectancy <= 0) {
            return "bot " + name + " v" + version + " has expectancy " + String.format(java.util.Locale.ROOT, "%.3f", expectancy) + "R over "
                    + reports.size() + " SIM sessions (" + rs.size() + " trades); PAPER needs it positive";
        }
        return null;
    }

    private static long paise(Object rupees) {
        if (rupees == null) {
            return 0;
        }
        return new BigDecimal(rupees.toString()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private static Double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
