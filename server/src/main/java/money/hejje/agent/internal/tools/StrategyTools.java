package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.MarketContextTools.NO_INPUT;
import static money.hejje.agent.internal.tools.ToolSupport.name;
import static money.hejje.agent.internal.tools.ToolSupport.rupees;
import static money.hejje.agent.internal.tools.ToolSupport.schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolException;
import money.hejje.agent.internal.tools.MarketContextTools.NoInput;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestService;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import money.hejje.recommend.Recommendation;
import money.hejje.recommend.RecommendationService;
import money.hejje.recommend.TodayView;
import money.hejje.scoring.ComparisonRow;
import money.hejje.scoring.ScoreBreakdown;
import money.hejje.scoring.ScoringService;
import money.hejje.scoring.VersionComparison;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.signals.SignalStatus;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.dsl.Condition;
import org.springframework.stereotype.Component;

/** Strategy tools (scope {@code strategies:read}): library, definitions, rankings as PRD 29 decision objects, backtests, comparisons, signals. */
@Component
public class StrategyTools implements AgentToolProvider {

    public record StrategyItem(UUID strategyId, String slug, String name, String family, int latestVersion, UUID latestVersionId, String latestStatus,
            Integer hejjeScore) {}

    public record StrategyList(List<StrategyItem> strategies) {}

    public record StrategyInput(String strategy, Integer version) {}

    public record ScoreComponentView(String name, double weight, double score, double contribution) {}

    public record AdjustmentView(String name, int delta, List<String> evidence) {}

    public record ScoreView(int finalScore, double base, String cap, UUID instrumentId, String instrument, UUID backtestId, Instant computedAt,
            List<ScoreComponentView> components, List<AdjustmentView> adjustments) {}

    public record DeploymentView(UUID deploymentId, String mode, boolean enabled, int autonomyLevel, List<String> instruments) {}

    public record StrategyDetail(UUID strategyId, String slug, String name, String family, UUID versionId, int version, String status, String changeNote,
            String description, String timeframe, String direction, List<String> universe, String entryMode, List<String> entryConditions, String exitMode,
            List<String> exitConditions, String stop, String target, String trailingStop, String tradeWindow, String forceExitTime, int maxTradesPerDay,
            Integer maxHoldingMinutes, Map<String, String> regimePreferences, String eventRules, ScoreView score, List<DeploymentView> deployments) {}

    public record RankingsInput(Integer limit) {}

    /** One Strategy Context Card row (PRD 19). */
    public record ContextRow(String name, String status, String value, Integer delta) {}

    /** PRD 29 agent decision object, plus the Context Card rows. */
    public record DecisionObject(UUID versionId, UUID strategyId, String strategy, int version, UUID instrumentId, String instrument, Integer score,
            String decision, String direction, UUID signalId, Instant signalValidUntil, BigDecimal entry, BigDecimal stop, BigDecimal target, Integer quantity,
            BigDecimal riskRupees, String regime, Double newsBias, String eventRisk, String nextEvent, List<String> hardBlocks, List<String> cautions,
            List<ContextRow> context) {}

    public record Rankings(Instant asOf, DecisionObject best, List<DecisionObject> ranked, String noTrade) {}

    public record BacktestQuery(String backtestId, String versionId) {}

    public record SplitMetrics(String split, int trades, double winRate, Double profitFactor, double expectancyR, double maxDrawdownR, BigDecimal netPnl,
            Double sharpe) {}

    public record WarningView(String code, String severity, String message) {}

    public record BacktestView(UUID backtestId, UUID versionId, String status, LocalDate from, LocalDate to, String timeframe, int slippageBps,
            List<SplitMetrics> metrics, List<WarningView> warnings, int sessionsExpected, int sessionsWithData, String resultHash, Instant finishedAt) {}

    public record CompareInput(List<String> versionIds) {}

    public record Comparison(List<ComparisonRow> rows) {}

    public record VersionsInput(String strategy, int a, int b) {}

    public record SignalInput(String signalId, String status, Integer limit) {}

    public record SignalView(UUID signalId, UUID versionId, UUID strategyId, UUID deploymentId, UUID instrumentId, String instrument, String mode, String side,
            BigDecimal referencePrice, BigDecimal stop, BigDecimal target, BigDecimal riskPerUnit, Instant barTime, Instant validUntil, String status, String note,
            List<Map<String, Object>> evidence) {}

    public record Signals(List<SignalView> signals) {}

    static final String STRATEGY_PROP = """
            {"type":"string","minLength":1,"description":"Strategy id or slug (e.g. nifty_orb)"}""";

    private final StrategyService strategies;
    private final ScoringService scoring;
    private final RecommendationService recommendations;
    private final BacktestService backtests;
    private final SignalService signals;
    private final ToolSupport support;
    private final HejjeClock clock;

    StrategyTools(StrategyService strategies, ScoringService scoring, RecommendationService recommendations, BacktestService backtests, SignalService signals,
            ToolSupport support, HejjeClock clock) {
        this.strategies = strategies;
        this.scoring = scoring;
        this.recommendations = recommendations;
        this.backtests = backtests;
        this.signals = signals;
        this.support = support;
        this.clock = clock;
    }

    @Override
    public List<AgentTool> tools() {
        return List.of(
                AgentTool.of("list_strategies", "Every strategy in the library with its latest version, lifecycle status and headline Hejje Score.",
                        ScopeCatalog.STRATEGIES_READ, NO_INPUT, NoInput.class, StrategyList.class, this::list),
                AgentTool.of("get_strategy", "One strategy version (default the latest): rules in words (entry/exit conditions, stop, target, trailing, window), "
                        + "regime preferences, event rules, the best instrument's score breakdown and its deployments.", ScopeCatalog.STRATEGIES_READ, schema("""
                                {"type":"object","properties":{"strategy":%s,"version":{"type":"integer","minimum":1}},
                                 "required":["strategy"],"additionalProperties":false}""".formatted(STRATEGY_PROP)),
                        StrategyInput.class, StrategyDetail.class, this::strategy),
                AgentTool.of("get_strategy_rankings", "Today's ranked recommendations as PRD 29 decision objects (score, TRADE / TRADE_WITH_CAUTION / WAIT / "
                        + "AVOID, direction, entry/stop/target, risk, regime, news bias, event risk, hard blocks, cautions) and the best one.",
                        ScopeCatalog.STRATEGIES_READ, schema("""
                                {"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":20}},"additionalProperties":false}"""),
                        RankingsInput.class, Rankings.class, this::rankings),
                AgentTool.of("get_strategy_backtest", "A backtest by id, or the base backtest of a version: metrics overall and per split (IS / validation / OOS), "
                        + "quality warnings, data coverage and the result hash.", ScopeCatalog.STRATEGIES_READ, schema("""
                                {"type":"object","properties":{"backtestId":{"type":"string","format":"uuid"},"versionId":{"type":"string","format":"uuid"}},
                                 "additionalProperties":false}"""),
                        BacktestQuery.class, BacktestView.class, this::backtest),
                AgentTool.of("compare_strategies", "Side-by-side comparison of 2 to 6 strategy versions: trades, win rate, profit factor, expectancy (R), max "
                        + "drawdown (R), similar-regime performance and Hejje Score.", ScopeCatalog.STRATEGIES_READ, schema("""
                                {"type":"object","properties":{"versionIds":{"type":"array","items":{"type":"string","format":"uuid"},"minItems":2,"maxItems":6}},
                                 "required":["versionIds"],"additionalProperties":false}"""),
                        CompareInput.class, Comparison.class, this::compare),
                AgentTool.of("compare_strategy_versions", "Compare two versions of one strategy: both rows, metric deltas and a templated verdict.",
                        ScopeCatalog.STRATEGIES_READ, schema("""
                                {"type":"object","properties":{"strategy":%s,"a":{"type":"integer","minimum":1},"b":{"type":"integer","minimum":1}},
                                 "required":["strategy","a","b"],"additionalProperties":false}""".formatted(STRATEGY_PROP)),
                        VersionsInput.class, VersionComparison.class, this::versions),
                AgentTool.of("get_strategy_signal", "A signal by id, today's signals with a given status, or (default) every active signal: side, reference price, "
                        + "stop, target, validity and the evidence that fired it.", ScopeCatalog.STRATEGIES_READ, schema("""
                                {"type":"object","properties":{"signalId":{"type":"string","format":"uuid"},"status":{"type":"string","enum":%s},
                                 "limit":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}""".formatted(ToolSupport.enumJson(SignalStatus.class))),
                        SignalInput.class, Signals.class, this::signal));
    }

    StrategyList list(NoInput in, ToolContext ctx) {
        return new StrategyList(strategies.list().stream().map(s -> new StrategyItem(s.id(), s.slug(), s.name(), name(s.family()), s.latestVersion(),
                s.latestVersionId(), name(s.latestStatus()), s.latestVersionId() == null ? null : scoring.headline(s.latestVersionId()).orElse(null))).toList());
    }

    Strategy resolve(String ref) {
        try {
            UUID id = UUID.fromString(ref.trim());
            return strategies.find(id).orElseThrow(() -> ToolException.notFound("Unknown strategy " + ref));
        } catch (IllegalArgumentException notAnId) {
            return strategies.findBySlug(ref.trim()).orElseThrow(() -> ToolException.notFound("Unknown strategy " + ref));
        }
    }

    StrategyDetail strategy(StrategyInput in, ToolContext ctx) {
        Strategy s = resolve(in.strategy());
        StrategyVersion v = in.version() == null
                ? strategies.latestVersion(s.id()).orElseThrow(() -> ToolException.notFound("Strategy " + s.slug() + " has no versions"))
                : strategies.version(s.id(), in.version()).orElseThrow(() -> ToolException.notFound("Strategy " + s.slug() + " has no version " + in.version()));
        StrategyDefinition d = v.definition();
        Function<UUID, String> symbols = support.symbols();
        ScoreView score = scoring.latestForVersion(v.id()).stream().max(Comparator.comparingInt(ScoreBreakdown::finalScore))
                .map(b -> scoreView(b, symbols)).orElse(null);
        List<DeploymentView> deployments = strategies.deployments(v.id(), null, null).stream()
                .map(dep -> new DeploymentView(dep.id(), name(dep.mode()), dep.enabled(), dep.autonomyLevel(), dep.instrumentIds().stream().map(symbols).toList()))
                .toList();
        Map<String, String> prefs = new LinkedHashMap<>();
        if (d.regimePreferences() != null) {
            d.regimePreferences().forEach((k, p) -> prefs.put(k, name(p)));
        }
        return new StrategyDetail(s.id(), s.slug(), s.name(), name(s.family()), v.id(), v.version(), name(v.status()), v.changeNote(), d.description(),
                name(d.timeframe()), name(d.direction()), universe(d), d.entry() == null ? null : name(d.entry().mode()), conditions(d.entry()),
                d.exit() == null ? null : name(d.exit().mode()), conditions(d.exit()),
                d.stop() == null ? null : spec(d.stop().type(), d.stop().value()), d.target() == null ? null : spec(d.target().type(), d.target().value()),
                d.trailingStop() == null ? null : spec(d.trailingStop().type(), d.trailingStop().value()),
                d.tradeWindow() == null ? null : d.tradeWindow().start() + "-" + d.tradeWindow().end(), d.forceExitTime() == null ? null : d.forceExitTime().toString(),
                d.maxTradesPerDay(), d.maxHoldingMinutes(), prefs,
                d.eventRules() == null ? null : name(d.eventRules().action()) + (d.eventRules().highRiskEventWithinMinutes() == null ? ""
                        : " when a high-risk event is within " + d.eventRules().highRiskEventWithinMinutes() + " minutes"),
                score, deployments);
    }

    /** Rules in words: the DSL text of each condition. */
    static List<String> conditions(StrategyDefinition.RuleSet rules) {
        return rules == null ? List.of() : rules.conditions().stream().map(Condition::text).toList();
    }

    static List<String> universe(StrategyDefinition d) {
        return d.universe() == null ? List.of()
                : d.universe().stream().map(u -> u.kind() == StrategyDefinition.UniverseKind.SYMBOL ? u.value() : u.kind().name().toLowerCase() + ":" + u.value())
                        .toList();
    }

    static String spec(Enum<?> type, BigDecimal value) {
        return type.name().toLowerCase() + (value == null ? "" : " " + value.stripTrailingZeros().toPlainString());
    }

    private ScoreView scoreView(ScoreBreakdown b, Function<UUID, String> symbols) {
        return new ScoreView(b.finalScore(), b.base(), b.cap(), b.instrumentId(), symbols.apply(b.instrumentId()), b.baseBacktestId(), b.computedAt(),
                b.components().stream().map(c -> new ScoreComponentView(c.name(), c.weight(), c.score(), c.contribution())).toList(),
                b.adjustments().stream().map(a -> new AdjustmentView(a.name(), a.delta(), a.evidence())).toList());
    }

    Rankings rankings(RankingsInput in, ToolContext ctx) {
        int limit = in.limit() == null ? 10 : in.limit();
        TodayView today = recommendations.today();
        return new Rankings(clock.now(), today.best() == null ? null : decision(today.best()),
                today.ranked().stream().limit(limit).map(StrategyTools::decision).toList(), today.noTrade());
    }

    static DecisionObject decision(Recommendation r) {
        return new DecisionObject(r.versionId(), r.strategyId(), r.strategy(), r.version(), r.instrumentId(), r.instrument(), r.score(), name(r.decision()),
                name(r.direction()), r.signalId(), r.signalValidUntil(), r.entry(), r.stop(), r.target(), r.quantity(), r.riskRupees(), r.regime(), r.newsBias(),
                r.eventRisk(), r.nextEvent(), r.hardBlocks(), r.cautions() == null ? List.of() : r.cautions().stream().map(c -> c.code() + ": " + c.message()).toList(),
                r.context() == null ? List.of() : r.context().items().stream().map(c -> new ContextRow(c.name(), name(c.status()), c.value(), c.delta())).toList());
    }

    BacktestView backtest(BacktestQuery in, ToolContext ctx) {
        Backtest b;
        if (in.backtestId() != null) {
            b = backtests.get(UUID.fromString(in.backtestId())).orElseThrow(() -> ToolException.notFound("Unknown backtest " + in.backtestId()));
        } else if (in.versionId() != null) {
            UUID v = UUID.fromString(in.versionId());
            b = backtests.baseBacktest(v).or(() -> backtests.list(v).stream().filter(x -> x.spec().sessionFilter() == null).findFirst()) // never a research-filtered run
                    .orElseThrow(() -> ToolException.notFound("No backtest for version " + in.versionId()));
        } else {
            throw ToolException.invalid("Give backtestId or versionId");
        }
        List<SplitMetrics> metrics = new ArrayList<>();
        if (b.metrics() != null) {
            metrics.add(split("ALL", b.metrics()));
        }
        if (b.bySplit() != null) {
            new TreeMap<>(b.bySplit()).forEach((k, m) -> metrics.add(split(k.name(), m)));
        }
        List<WarningView> warnings = b.warnings() == null ? List.of()
                : b.warnings().stream().map(w -> new WarningView(w.code(), name(w.severity()), w.message())).toList();
        return new BacktestView(b.id(), b.versionId(), name(b.status()), b.spec().from(), b.spec().to(), name(b.spec().timeframe()), b.spec().slippageBps(), metrics,
                warnings, b.sessionsExpected(), b.sessionsWithData(), b.resultHash(), b.finishedAt());
    }

    private static SplitMetrics split(String name, BacktestMetrics m) {
        return new SplitMetrics(name, m.totalTrades(), m.winRate(), m.profitFactor(), m.expectancyR(), m.maxDrawdownR(), rupees(m.netPnl()), m.sharpe());
    }

    Comparison compare(CompareInput in, ToolContext ctx) {
        return new Comparison(scoring.compare(in.versionIds().stream().map(UUID::fromString).toList()));
    }

    VersionComparison versions(VersionsInput in, ToolContext ctx) {
        return scoring.compareVersions(resolve(in.strategy()).id(), in.a(), in.b());
    }

    Signals signal(SignalInput in, ToolContext ctx) {
        Function<UUID, String> symbols = support.symbols();
        if (in.signalId() != null) {
            Signal s = signals.find(UUID.fromString(in.signalId())).orElseThrow(() -> ToolException.notFound("Unknown signal " + in.signalId()));
            return new Signals(List.of(view(s, symbols)));
        }
        int limit = in.limit() == null ? 20 : in.limit();
        List<Signal> list = in.status() == null ? signals.active()
                : signals.list(SignalStatus.valueOf(in.status()), clock.today().atStartOfDay(clock.zone()).toInstant(), limit);
        return new Signals(list.stream().limit(limit).map(s -> view(s, symbols)).toList());
    }

    private static SignalView view(Signal s, Function<UUID, String> symbols) {
        return new SignalView(s.id(), s.versionId(), s.strategyId(), s.deploymentId(), s.instrumentId(), symbols.apply(s.instrumentId()), name(s.mode()),
                name(s.side()), s.referencePrice(), s.stop(), s.target(), s.riskPerUnit(), s.barTime(), s.validUntil(), name(s.status()), s.note(), s.evidence());
    }
}
