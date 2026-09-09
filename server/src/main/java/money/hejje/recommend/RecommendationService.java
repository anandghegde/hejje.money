package money.hejje.recommend;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestService;
import money.hejje.common.Ids;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.MarketProperties;
import money.hejje.market.MarketService;
import money.hejje.market.QuoteSnapshot;
import money.hejje.recommend.internal.RecommendationStore;
import money.hejje.risk.RiskCheck;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreBreakdown;
import money.hejje.scoring.ScoringService;
import money.hejje.signals.PreparedOrder;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.springframework.stereotype.Service;

/**
 * PRD section 73: {@code score × signal validity × risk eligibility = recommendation}. For each enabled deployment ×
 * instrument in the current mode: TRADE when an unexpired signal exists, the dry-run risk decision approves it and the
 * score reaches {@code hejje.recommend.min-score}; AVOID when a hard block (risk rejection, kill switch, readiness)
 * stands; WAIT otherwise. Each decision for a signal is recorded once per (signal, decision) in {@code recommendation}.
 */
@Service
public class RecommendationService {

    private final StrategyService strategies;
    private final SignalService signals;
    private final ScoringService scoring;
    private final BacktestService backtests;
    private final InstrumentService instruments;
    private final MarketService market;
    private final MarketProperties marketProperties;
    private final RecommendationStore store;
    private final RecommendProperties properties;
    private final HejjeProperties hejje;
    private final HejjeClock clock;

    RecommendationService(StrategyService strategies, SignalService signals, ScoringService scoring, BacktestService backtests, InstrumentService instruments,
            MarketService market, MarketProperties marketProperties, RecommendationStore store, RecommendProperties properties, HejjeProperties hejje,
            HejjeClock clock) {
        this.strategies = strategies;
        this.signals = signals;
        this.scoring = scoring;
        this.backtests = backtests;
        this.instruments = instruments;
        this.market = market;
        this.marketProperties = marketProperties;
        this.store = store;
        this.properties = properties;
        this.hejje = hejje;
        this.clock = clock;
    }

    public TodayView today() {
        List<Recommendation> ranked = rank();
        Recommendation best = ranked.stream().filter(r -> r.decision() == Decision.TRADE).findFirst().orElse(null);
        String noTrade = best != null ? null : ranked.isEmpty()
                ? "No strategies deployed. Deploy a validated strategy to see recommendations."
                : "No strategy currently meets your minimum quality threshold (" + properties.minScore() + ").";
        return new TodayView(header(), best, ranked, noTrade);
    }

    /** Every deployed strategy × instrument, ordered TRADE first, then by score. */
    public List<Recommendation> rank() {
        List<Recommendation> out = new ArrayList<>();
        Map<String, Signal> active = new LinkedHashMap<>();
        for (Signal s : signals.active()) {
            active.put(s.versionId() + "|" + s.instrumentId(), s);
        }
        for (StrategyDeployment d : strategies.deployments(null, hejje.mode(), true)) {
            Optional<StrategyVersion> version = strategies.versionById(d.versionId());
            if (version.isEmpty()) {
                continue;
            }
            Strategy strategy = strategies.find(version.get().strategyId()).orElse(null);
            for (UUID instrumentId : d.instrumentIds()) {
                Recommendation r = recommend(strategy, version.get(), d, instrumentId, active.get(d.versionId() + "|" + instrumentId));
                out.add(r);
                if (r.signalId() != null) {
                    store.recordIfChanged(r, hejje.mode(), clock.now());
                }
            }
        }
        out.sort(Comparator.comparing((Recommendation r) -> r.decision().ordinal()).thenComparing(r -> -(r.score() == null ? -1 : r.score())));
        return out;
    }

    private Recommendation recommend(Strategy strategy, StrategyVersion version, StrategyDeployment d, UUID instrumentId, Signal signal) {
        Instrument instrument = instruments.findById(instrumentId).orElse(null);
        String symbol = instrument == null ? instrumentId.toString() : instrument.hejjeSymbol().format();
        Optional<ScoreBreakdown> score = scoring.latest(version.id(), instrumentId);
        Integer finalScore = score.map(ScoreBreakdown::finalScore).orElse(null);
        List<String> evidence = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> hardBlocks = new ArrayList<>();
        Map<String, Object> backtest = backtestSummary(version.id());

        Decision decision;
        Integer quantity = null;
        BigDecimal entry = null;
        BigDecimal riskRupees = null;
        BigDecimal reward = null;
        if (signal == null) {
            decision = Decision.WAIT;
            risks.add("No active signal: waiting for the setup to form");
        } else {
            for (Map<String, Object> e : signal.evidence()) {
                evidence.add(("PASSED".equals(e.get("status")) ? "✓ " : "✗ ") + e.get("condition")
                        + (e.get("lhs") == null ? "" : " (" + fmt(e.get("lhs")) + " vs " + fmt(e.get("rhs")) + ")"));
            }
            PreparedOrder dry = signals.dryRun(signal, null);
            quantity = (Integer) dry.sizing().get("quantity");
            entry = new BigDecimal(String.valueOf(dry.sizing().get("entryReference")));
            riskRupees = new BigDecimal(String.valueOf(dry.sizing().get("riskRupees")));
            for (RiskCheck check : dry.risk().checks()) {
                if (!check.passed()) {
                    hardBlocks.add(check.name() + ": " + check.message());
                }
            }
            dry.notes().forEach(hardBlocks::add);
            if (signal.target() != null && signal.riskPerUnit().signum() > 0) {
                BigDecimal rr = signal.target().subtract(entry).abs().divide(entry.subtract(signal.stop()).abs(), 2, RoundingMode.HALF_UP);
                reward = riskRupees.multiply(rr).setScale(0, RoundingMode.HALF_UP);
                evidence.add("✓ Reward:risk " + rr.toPlainString() + " at the current price");
                if (rr.compareTo(BigDecimal.ONE) < 0) {
                    risks.add("⚠ Reward:risk has fallen below 1 since the signal bar");
                }
            }
            if (!hardBlocks.isEmpty()) {
                decision = Decision.AVOID;
            } else if (finalScore == null) {
                decision = Decision.WAIT;
                risks.add("⚠ Strategy has no Hejje Score yet (no completed backtest)");
            } else if (finalScore < properties.minScore()) {
                decision = Decision.WAIT;
                risks.add("⚠ Hejje Score " + finalScore + " is below the minimum " + properties.minScore());
            } else {
                decision = Decision.TRADE;
            }
        }
        score.ifPresent(s -> {
            for (Adjustment a : s.adjustments()) {
                if (a.delta() > 0) {
                    evidence.add("✓ " + a.name() + " +" + a.delta());
                } else if (a.delta() < 0) {
                    risks.add("⚠ " + a.name() + " " + a.delta());
                }
            }
            if (s.cap() != null) {
                risks.add("⚠ Base score " + s.cap());
            }
        });
        if (backtest.containsKey("expectancyR")) {
            evidence.add("✓ Backtested expectancy " + backtest.get("expectancyR") + "R, win rate " + backtest.get("winRatePct") + "%");
        }
        Map<String, Object> breakdown = score.map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("base", s.base());
            m.put("adjustments", s.adjustments().stream().map(a -> Map.of("name", a.name(), "delta", a.delta())).toList());
            m.put("final", s.finalScore());
            m.put("computedAt", s.computedAt().toString());
            return m;
        }).orElse(Map.of());
        return new Recommendation(version.id(), version.strategyId(), strategy == null ? version.definition().name() : strategy.slug(), version.version(), d.id(),
                instrumentId, symbol, finalScore, decision, signal == null ? null : signal.side(), signal == null ? null : signal.id(),
                signal == null ? null : signal.status().name(), signal == null ? null : signal.validUntil(), entry, signal == null ? null : signal.stop(),
                signal == null ? null : signal.target(), quantity, riskRupees, reward, null, null, "UNKNOWN", hardBlocks, evidence, risks, backtest, breakdown);
    }

    private Map<String, Object> backtestSummary(UUID versionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<Backtest> base = backtests.baseBacktest(versionId);
        if (base.isEmpty() || base.get().metrics() == null) {
            return m;
        }
        BacktestMetrics x = base.get().metrics();
        m.put("backtestId", base.get().id().toString());
        m.put("trades", x.totalTrades());
        m.put("expectancyR", round(x.expectancyR(), 2));
        m.put("winRatePct", Math.round(x.winRate() * 100));
        m.put("profitFactor", x.profitFactor() == null ? null : round(x.profitFactor(), 2));
        m.put("maxDrawdownR", round(x.maxDrawdownR(), 1));
        return m;
    }

    private Map<String, Object> header() {
        Map<String, Object> header = new LinkedHashMap<>();
        Map<String, Object> quotes = new LinkedHashMap<>();
        for (String symbol : marketProperties.watchlist()) {
            instruments.resolve(symbol).flatMap(i -> market.quote(i.id())).ifPresent(q -> quotes.put(symbol, quoteView(q)));
        }
        header.put("indexQuotes", quotes);
        header.put("vix", quotes.containsKey("INDEX:INDIA VIX") ? ((Map<?, ?>) quotes.get("INDEX:INDIA VIX")).get("lastPrice") : null);
        header.put("regime", null);
        header.put("breadth", null);
        header.put("eventRisk", null);
        header.put("mode", hejje.mode().name());
        header.put("time", clock.now().toString());
        return header;
    }

    private static Map<String, Object> quoteView(QuoteSnapshot q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastPrice", q.lastPrice());
        m.put("ts", q.ts().toString());
        m.put("stale", q.stale());
        return m;
    }

    public List<Recommendation> history(UUID signalId) {
        return store.history(signalId);
    }

    private static String fmt(Object v) {
        return v instanceof Number n ? String.format("%.2f", n.doubleValue()) : String.valueOf(v);
    }

    private static double round(double v, int places) {
        double p = Math.pow(10, places);
        return Math.round(v * p) / p;
    }
}
