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
import money.hejje.events.EventRisk;
import money.hejje.events.EventRuleOutcome;
import money.hejje.events.EventService;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsService;
import money.hejje.context.ContextItem;
import money.hejje.context.ContextService;
import money.hejje.context.StrategyContext;
import money.hejje.pulse.PulseService;
import money.hejje.risk.RiskService;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.jev.SignalCheck;
import money.hejje.market.MarketProperties;
import money.hejje.market.MarketService;
import money.hejje.market.QuoteSnapshot;
import money.hejje.recommend.internal.RecommendationStore;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
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

    private final SignalCheck jevCheck;
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
    private final RegimeService regime;
    private final EventService events;
    private final NewsService news;
    private final ContextService context;
    private final PulseService pulse;
    private final RiskService risk;
    private final HejjeClock clock;

    RecommendationService(StrategyService strategies, SignalService signals, ScoringService scoring, BacktestService backtests, InstrumentService instruments,
            MarketService market, MarketProperties marketProperties, RecommendationStore store, RecommendProperties properties, HejjeProperties hejje,
            RegimeService regime, EventService events, NewsService news, ContextService context, PulseService pulse, RiskService risk, HejjeClock clock,
            SignalCheck jevCheck) {
        this.jevCheck = jevCheck;
        this.regime = regime;
        this.events = events;
        this.news = news;
        this.context = context;
        this.pulse = pulse;
        this.risk = risk;
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
        Recommendation best = ranked.stream().filter(r -> r.decision() == Decision.TRADE || r.decision() == Decision.TRADE_WITH_CAUTION).findFirst().orElse(null);
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
        EventRisk eventRisk = events.risk(instrumentId);
        EventRuleOutcome eventRule = money.hejje.events.EventRules.apply(version.definition().eventRules(), eventRisk);
        String nextEvent = events.nextEventLine(eventRisk);
        NewsBias newsBias = news.bias(instrumentId);

        Decision decision;
        Integer quantity = null;
        BigDecimal entry = null;
        BigDecimal riskRupees = null;
        BigDecimal reward = null;
        List<Caution> cautions = new ArrayList<>();
        boolean outsideWindow = false;
        if (signal == null) {
            risks.add("No active signal: waiting for the setup to form");
        } else {
            for (Map<String, Object> e : signal.evidence()) {
                if (e.containsKey("jevCheck")) {
                    continue; // the Jev signal check is an annotation, shown below
                }
                evidence.add(("PASSED".equals(e.get("status")) ? "✓ " : "✗ ") + e.get("condition")
                        + (e.get("lhs") == null ? "" : " (" + fmt(e.get("lhs")) + " vs " + fmt(e.get("rhs")) + ")"));
            }
            PreparedOrder dry = signals.dryRun(signal, null);
            quantity = (Integer) dry.sizing().get("quantity");
            entry = new BigDecimal(String.valueOf(dry.sizing().get("entryReference")));
            riskRupees = new BigDecimal(String.valueOf(dry.sizing().get("riskRupees")));
            for (RiskCheck check : dry.risk().checks()) {
                if (!check.passed()) {
                    if (check.name().equals("tradingWindow")) {
                        outsideWindow = true;
                        risks.add("⚠ Outside the trading window: " + check.message());
                    } else {
                        hardBlocks.add(check.name() + ": " + check.message());
                    }
                }
            }
            for (String note : dry.notes()) {
                if (hardBlocks.stream().noneMatch(b -> b.endsWith(note))) {
                    hardBlocks.add(note);
                }
            }
            if (signal.target() != null && signal.riskPerUnit().signum() > 0) {
                BigDecimal rr = signal.target().subtract(entry).abs().divide(entry.subtract(signal.stop()).abs(), 2, RoundingMode.HALF_UP);
                reward = riskRupees.multiply(rr).setScale(0, RoundingMode.HALF_UP);
                evidence.add("✓ Reward:risk " + rr.toPlainString() + " at the current price");
                if (rr.compareTo(BigDecimal.ONE) < 0) {
                    risks.add("⚠ Reward:risk has fallen below 1 since the signal bar");
                }
                BigDecimal strategyMin = version.definition().riskOverrides() == null ? null : version.definition().riskOverrides().minRewardRisk();
                if (strategyMin != null && rr.compareTo(strategyMin) < 0) {
                    cautions.add(new Caution("REWARD_RISK", "Reward:risk " + rr.toPlainString() + " is below the strategy's minimum " + strategyMin.toPlainString()
                            + " (above the global minimum " + globalMinRewardRisk() + ")"));
                }
            }
            if (eventRule.blocks()) {
                if (hardBlocks.stream().noneMatch(b -> b.startsWith("eventRule"))) {
                    hardBlocks.add("eventRule: " + eventRule.reason());
                }
            } else if (eventRule.cautions()) {
                cautions.add(new Caution("EVENT_CAUTION", eventRule.reason()));
            }
            pulse.vixChangePct().filter(v -> v > properties.caution().vixRisePct())
                    .ifPresent(v -> cautions.add(new Caution("VIX_RISING", String.format(java.util.Locale.ROOT, "India VIX up %.1f%% on the day", v))));
            regimeSnapshot().ifPresent(snapshot -> version.definition().regimePreferences().forEach((key, pref) -> {
                if (pref == money.hejje.strategy.StrategyDefinition.RegimePreference.AVOID && snapshot.matches(key)) {
                    cautions.add(new Caution("REGIME_AVOID", "Strategy avoids the '" + key + "' regime, which is current (" + snapshot.key() + ")"));
                }
            }));
            if (newsBias.available()) {
                double opposing = signal.side() == Side.BUY ? -newsBias.score() : newsBias.score();
                if (opposing >= properties.caution().newsOpposingScore()) {
                    cautions.add(new Caution("NEWS_OPPOSING", String.format(java.util.Locale.ROOT, "News bias %s %+.2f opposes the %s signal", newsBias.label(),
                            newsBias.score(), signal.side() == Side.BUY ? "long" : "short")));
                }
            }
            // plan M9.5: the Jev signal check, shown when present; a disagreement is a caution from gate=caution up
            Optional<SignalCheck.Result> jev = SignalCheck.stored(signal);
            if (jev.isPresent()) {
                if (jev.get().agrees()) {
                    evidence.add("✓ " + jev.get().line());
                } else if (jevCheck.gate() == SignalCheck.Gate.OFF) {
                    risks.add("⚠ " + jev.get().line());
                } else {
                    cautions.add(new Caution(SignalCheck.DISAGREES, jev.get().line()));
                }
            }
            // stale beyond the readiness threshold is already a hard block; the cache's own staleness flag covers the gap before it
            Optional<QuoteSnapshot> quote = market.quote(instrumentId);
            if (quote.isEmpty() || quote.get().stale()) {
                cautions.add(new Caution("MARKET_DATA_STALE", quote.isEmpty() ? "No quote for the instrument"
                        : "Last quote is " + java.time.Duration.between(quote.get().ts(), clock.now()).getSeconds() + " s old (stale after "
                                + marketProperties.quoteStaleAfter().getSeconds() + " s)"));
            }
            for (Caution c : cautions) {
                risks.add("⚠ " + c.message());
            }
            if (finalScore == null && hardBlocks.isEmpty() && !outsideWindow) {
                risks.add("⚠ Strategy has no Hejje Score yet (no completed backtest)");
            } else if (finalScore != null && finalScore < properties.minScore() && hardBlocks.isEmpty() && !outsideWindow) {
                risks.add("⚠ Hejje Score " + finalScore + " is below the minimum " + properties.minScore());
            }
        }
        decision = DecisionRules.decide(signal != null, hardBlocks, outsideWindow, finalScore, properties.minScore(), cautions);
        StrategyContext card = null;
        try {
            card = context.strategyContext(version.id(), instrumentId);
            for (ContextItem item : card.items()) {
                if (item.status() == ContextItem.Status.GREEN) {
                    evidence.add("✓ " + contextLine(item));
                } else if (item.status() == ContextItem.Status.RED) {
                    risks.add("⚠ " + contextLine(item));
                }
            }
        } catch (RuntimeException e) {
            // the card is optional context; Today renders without it
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
                signal == null ? null : signal.target(), quantity, riskRupees, reward, regimeLabel(), newsBias.available() ? newsBias.score() : null, eventRisk.available() ? eventRisk.level().name() : "UNKNOWN",
                nextEvent, hardBlocks, cautions, evidence, risks, backtest, breakdown, card);
    }

    private static String contextLine(ContextItem item) {
        return switch (item.name()) {
            case "Market regime" -> item.status() == ContextItem.Status.GREEN ? "Strategy performs strongly in the current regime" : "Strategy performs poorly in the current regime";
            case "Sector" -> "Sector " + item.value().toLowerCase(java.util.Locale.ROOT) + (item.status() == ContextItem.Status.GREEN ? " (outperforming)" : " (underperforming)");
            case "News bias" -> "News bias " + item.value();
            case "Event risk" -> "Event risk " + item.value();
            default -> item.name() + " " + item.value().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private Optional<RegimeSnapshot> regimeSnapshot() {
        try {
            RegimeSnapshot s = regime.current();
            return s.isUnknown() ? Optional.empty() : Optional.of(s);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String globalMinRewardRisk() {
        try {
            return risk.limits(hejje.mode()).minRewardRisk().toPlainString();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private String regimeLabel() {
        try {
            RegimeSnapshot s = regime.current();
            return s.isUnknown() ? "UNKNOWN" : s.key();
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
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
        RegimeSnapshot snapshot = null;
        try {
            snapshot = regime.current();
        } catch (RuntimeException e) {
            // the regime engine is optional: the header shows UNKNOWN
        }
        header.put("regime", snapshot == null ? "UNKNOWN" : snapshot.trend().name());
        header.put("regimeLabels", snapshot == null ? null : RegimeService.labelsOf(snapshot));
        header.put("breadth", snapshot == null ? "UNKNOWN" : snapshot.breadth().name());
        EventRisk marketRisk = events.risk(null);
        header.put("eventRisk", marketRisk.available() ? marketRisk.level().name() : "UNKNOWN");
        header.put("nextEvent", events.nextEventLine(marketRisk));
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
