package money.hejje.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import money.hejje.common.InstrumentType;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Timeframe;
import money.hejje.common.costs.CostFill;
import money.hejje.common.costs.CostModel;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.ratings.Base;
import money.hejje.ratings.RatingsProperties;
import money.hejje.ratings.RatingsService;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import org.springframework.stereotype.Service;

/**
 * Runs the SWING backtest (plan M11.5) over the M8.4 ledger's setups detected in a date range, on the stored D1 bars,
 * with delivery costs, and reports it the way intraday backtests are read: summary, per base type, per regime of the entry
 * session and walk-forward folds. {@code rules = LEDGER} replays the H5 ledger's rules instead (the parity check).
 */
@Service
public class SwingBacktestService {

    private final RatingsService ratings;
    private final RatingsProperties ratingsProperties;
    private final MarketService market;
    private final RegimeService regime;
    private final CostModel costs;
    private final SwingProperties swing;
    private final HejjeClock clock;

    SwingBacktestService(RatingsService ratings, RatingsProperties ratingsProperties, MarketService market, RegimeService regime, CostModel costs,
            SwingProperties swing, HejjeClock clock) {
        this.ratings = ratings;
        this.ratingsProperties = ratingsProperties;
        this.market = market;
        this.regime = regime;
        this.costs = costs;
        this.swing = swing;
        this.clock = clock;
    }

    public enum Rules { SWING, LEDGER }

    /**
     * @param symbols      Hejje symbols; empty = every symbol with a setup
     * @param types        base types to include; empty = all
     * @param riskRupees   money at risk per trade (gap-adjusted), default 2,500
     * @param folds        walk-forward folds over the entry dates, default 4
     */
    public record Request(LocalDate from, LocalDate to, List<String> symbols, List<String> types, Rules rules, Boolean volumeFilter, BigDecimal volumePace,
            Integer maxHoldingDays, BigDecimal gapAllowancePct, Long riskRupees, Integer folds) {}

    /** Aggregates of a group of trades; R before costs as the ledger measures it, net R after delivery costs. */
    public record Stats(int trades, int hitGoal, int stopped, int timeExit, int gapFills, int winners, Double meanR, Double meanNetR, Money net,
            Double profitFactor, Double meanHoldingDays) {}

    public record Group(String key, Stats stats) {}

    public record Fold(int fold, LocalDate from, LocalDate to, Stats stats) {}

    public record Report(Rules rules, SwingBacktest.Config config, LocalDate from, LocalDate to, int setups, Stats summary, List<Group> byType,
            List<Group> byRegime, List<Fold> walkForward, Map<SwingBacktest.NoTrade, Integer> notTraded, List<SwingBacktest.Trade> trades) {}

    public Report run(Request r) {
        LocalDate from = r.from();
        LocalDate to = r.to() == null ? clock.today() : r.to();
        if (from == null || from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        Rules rules = r.rules() == null ? Rules.SWING : r.rules();
        RatingsProperties.Bases b = ratingsProperties.bases();
        Money risk = Money.ofRupees(r.riskRupees() == null ? 2_500 : r.riskRupees());
        BigDecimal gap = r.gapAllowancePct() == null ? new BigDecimal("3") : r.gapAllowancePct();
        SwingBacktest.Config cfg = rules == Rules.LEDGER ? SwingBacktest.Config.ledger(b.maxHoldSessions(), b.expireSessions(), risk, gap)
                : new SwingBacktest.Config(SwingBacktest.Trigger.RANGE, r.volumeFilter() == null || r.volumeFilter(),
                        r.volumePace() == null ? swing.volumePace() : r.volumePace(), true,
                        r.maxHoldingDays() == null ? swing.maxHoldingDays() : r.maxHoldingDays(), SwingBacktest.TimeExit.NEXT_OPEN, b.expireSessions(), risk, gap);

        Set<String> symbols = r.symbols() == null ? Set.of() : Set.copyOf(r.symbols().stream().map(s -> s.trim().toUpperCase()).toList());
        Set<String> types = r.types() == null ? Set.of() : Set.copyOf(r.types().stream().map(s -> s.trim().toUpperCase()).toList());
        // every setup detected in the range, with its plan as recorded at detection (the plan never changes afterwards)
        List<Base> bases = ratings.bases(null, null, to).stream()
                .filter(x -> !x.detectedDate().isBefore(from) && !x.detectedDate().isAfter(to))
                .filter(x -> symbols.isEmpty() || symbols.contains(x.symbol())).filter(x -> types.isEmpty() || types.contains(x.type().name()))
                .sorted(Comparator.comparing(Base::detectedDate).thenComparing(Base::symbol)).toList();
        List<SwingBacktest.Plan> plans = bases.stream().map(x -> new SwingBacktest.Plan(x.id(), x.instrumentId(), x.symbol(), x.type(), x.detectedDate(),
                x.baseLow(), x.pivot(), x.buyHigh(), x.stop(), x.goal(), supersededOn(x, bases))).toList();

        Map<UUID, List<SwingBacktest.Bar>> bars = new LinkedHashMap<>();
        Instant start = from.minusDays(120).atStartOfDay(clock.zone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(clock.zone()).toInstant().minusSeconds(1);
        for (UUID id : plans.stream().map(SwingBacktest.Plan::instrumentId).distinct().toList()) {
            bars.put(id, market.candles(id, Timeframe.D1, start, end).stream().sorted(Comparator.comparing(Candle::openTime))
                    .map(c -> new SwingBacktest.Bar(c.openTime().atZone(clock.zone()).toLocalDate(), c.open(), c.high(), c.low(), c.close(), c.volume())).toList());
        }
        SwingBacktest.Result result = SwingBacktest.run(bars, plans, cfg,
                (side, qty, price) -> costs.compute(new CostFill(InstrumentType.EQ, Product.CNC, side, qty, price)).total());
        List<SwingBacktest.Trade> trades = result.trades();

        Map<LocalDate, RegimeSnapshot> labels = trades.isEmpty() ? Map.of()
                : regime.labels(trades.get(0).entryDate(), trades.get(trades.size() - 1).entryDate());
        return new Report(rules, cfg, from, to, plans.size(), stats(trades), groups(trades, t -> t.plan().type().name()),
                groups(trades, t -> {
                    RegimeSnapshot s = labels.get(t.entryDate());
                    return s == null ? "UNKNOWN × UNKNOWN" : s.key();
                }), folds(trades, r.folds() == null ? 4 : Math.max(1, r.folds())), result.notTraded(), trades);
    }

    /** An untriggered cup that the ledger replaced by its cup-with-handle, detected on the day the cup expired. */
    static LocalDate supersededOn(Base b, List<Base> all) {
        if (b.type() != money.hejje.ratings.BaseType.CUP || b.triggerDate() != null || b.status() != money.hejje.ratings.BaseStatus.EXPIRED) {
            return null;
        }
        boolean handle = all.stream().anyMatch(o -> o.instrumentId().equals(b.instrumentId()) && o.type() == money.hejje.ratings.BaseType.CUP_WITH_HANDLE
                && o.detectedDate().equals(b.statusDate()));
        return handle ? b.statusDate() : null;
    }

    static Stats stats(List<SwingBacktest.Trade> trades) {
        int goal = 0;
        int stopped = 0;
        int time = 0;
        int gaps = 0;
        int winners = 0;
        long net = 0;
        long won = 0;
        long lost = 0;
        double sumR = 0;
        double sumNetR = 0;
        int withR = 0;
        long days = 0;
        for (SwingBacktest.Trade t : trades) {
            switch (t.reason()) {
                case HIT_GOAL -> goal++;
                case STOPPED -> stopped++;
                case TIME_EXIT -> time++;
            }
            gaps += t.gapFill() ? 1 : 0;
            winners += t.net().paise() > 0 ? 1 : 0;
            net += t.net().paise();
            won += Math.max(0, t.net().paise());
            lost += Math.max(0, -t.net().paise());
            days += t.holdingDays();
            if (t.grossR() != null && t.netR() != null) {
                sumR += t.grossR();
                sumNetR += t.netR();
                withR++;
            }
        }
        int n = trades.size();
        return new Stats(n, goal, stopped, time, gaps, winners, withR == 0 ? null : round(sumR / withR), withR == 0 ? null : round(sumNetR / withR),
                Money.ofPaise(net), lost == 0 ? null : round((double) won / lost), n == 0 ? null : round((double) days / n));
    }

    static List<Group> groups(List<SwingBacktest.Trade> trades, Function<SwingBacktest.Trade, String> key) {
        Map<String, List<SwingBacktest.Trade>> by = new TreeMap<>();
        trades.forEach(t -> by.computeIfAbsent(key.apply(t), k -> new ArrayList<>()).add(t));
        return by.entrySet().stream().map(e -> new Group(e.getKey(), stats(e.getValue()))).toList();
    }

    /** Consecutive folds of (about) equal trade counts by entry date. */
    static List<Fold> folds(List<SwingBacktest.Trade> trades, int k) {
        List<Fold> out = new ArrayList<>();
        if (trades.isEmpty()) {
            return out;
        }
        int n = trades.size();
        int folds = Math.min(k, n);
        for (int f = 0; f < folds; f++) {
            List<SwingBacktest.Trade> part = trades.subList(f * n / folds, (f + 1) * n / folds);
            out.add(new Fold(f + 1, part.get(0).entryDate(), part.get(part.size() - 1).entryDate(), stats(part)));
        }
        return out;
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
