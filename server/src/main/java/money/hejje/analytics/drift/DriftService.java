package money.hejje.analytics.drift;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.analytics.TradeReview;
import money.hejje.analytics.drift.internal.DriftStore;
import money.hejje.analytics.internal.ReviewStore;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestService;
import money.hejje.backtest.Split;
import money.hejje.common.ActorType;
import money.hejje.common.ClientNotification;
import money.hejje.common.ExecutionMode;
import money.hejje.common.time.HejjeClock;
import money.hejje.scoring.ScoringService;
import money.hejje.signals.SignalService;
import money.hejje.signals.StrategyPosition;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Live-vs-backtest drift per deployment (plan M5.1): assessment ({@link #report}), evaluation with the configured actions
 * ({@link #evaluate}, once per escalation), manual override and the per-strategy view.
 */
@Service
public class DriftService {

    private static final Logger log = LoggerFactory.getLogger(DriftService.class);
    static final String SYSTEM = "system";

    private final DriftStore store;
    private final ReviewStore reviews;
    private final SignalService signals;
    private final StrategyService strategies;
    private final BacktestService backtests;
    private final ScoringService scoring;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final DriftProperties properties;
    private final money.hejje.common.config.AutoProperties auto;
    private final HejjeClock clock;

    DriftService(DriftStore store, ReviewStore reviews, SignalService signals, StrategyService strategies, BacktestService backtests, ScoringService scoring,
            AuditService audit, ApplicationEventPublisher events, DriftProperties properties, money.hejje.common.config.AutoProperties auto, HejjeClock clock) {
        this.auto = auto;
        this.store = store;
        this.reviews = reviews;
        this.signals = signals;
        this.strategies = strategies;
        this.backtests = backtests;
        this.scoring = scoring;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** One deployment in the per-strategy view: a fresh report, the stored state and the latest assessments. */
    public record DeploymentDrift(DriftReport report, DriftState state, List<HistoryItem> history) {}

    /** A stored assessment: the status reached, the actions it took (or why none) and the criteria. */
    public record HistoryItem(UUID id, DriftStatus status, List<String> actions, List<String> triggered, Instant at) {}

    public boolean enabled() {
        return properties.enabled();
    }

    // --- assessment ---

    /** The current comparison for one deployment, without side effects. */
    public DriftReport report(StrategyDeployment d) {
        StrategyVersion version = strategies.versionById(d.versionId()).orElseThrow();
        LocalDate to = clock.today();
        LocalDate from = sessionsBack(to, properties.trailingSessions());
        List<TradeReview> trades = trailing(d, from);
        List<Double> rs = trades.stream().map(TradeReview::outcomeR).toList();
        int wins = (int) trades.stream().filter(t -> t.netPnl().paise() > 0).count();
        Optional<Backtest> base = backtests.baseBacktest(d.versionId());
        DriftReport.Side backtestSide = base.map(DriftService::baselineSide).orElse(null);
        DriftMath.Baseline baseline = backtestSide == null ? null : new DriftMath.Baseline(backtestSide.trades(), backtestSide.winRate(),
                backtestSide.expectancyR(), backtestSide.profitFactor(), backtestSide.maxDrawdownR());
        DriftMath.Assessment a = DriftMath.assess(rs, wins, baseline, properties);
        List<String> evidence = new ArrayList<>(a.evidence());
        if (backtestSide != null && !"OUT_OF_SAMPLE".equals(backtestSide.split())) {
            evidence.add("the base backtest has no out-of-sample trades; compared against all of its trades");
        }
        return new DriftReport(d.id(), d.versionId(), version.version(), d.strategyId(), d.mode().name(), d.enabled(), d.sizeMultiplier(), a.status(),
                new DriftReport.Window(properties.trailingTrades(), properties.trailingSessions(), from, to), a.live(), backtestSide, a.stats(), a.triggered(),
                evidence, clock.now());
    }

    /** The out-of-sample slice of the base backtest when it has trades, else the whole run. */
    static DriftReport.Side baselineSide(Backtest b) {
        BacktestMetrics oos = b.bySplit() == null ? null : b.bySplit().get(Split.OUT_OF_SAMPLE);
        boolean useOos = oos != null && oos.totalTrades() > 0;
        BacktestMetrics m = useOos ? oos : b.metrics();
        if (m == null) {
            return null;
        }
        return new DriftReport.Side(m.totalTrades(), m.winRate(), m.expectancyR(), m.profitFactor(), m.maxDrawdownR(), b.id(), useOos ? "OUT_OF_SAMPLE" : "OVERALL");
    }

    /** Reviewed trades of this deployment closed since {@code from}, newest {@code trailingTrades}, oldest first. */
    private List<TradeReview> trailing(StrategyDeployment d, LocalDate from) {
        Instant since = from.atStartOfDay(clock.zone()).toInstant();
        List<TradeReview> mine = new ArrayList<>();
        for (TradeReview r : reviews.listStrategyTrades(d.strategyId(), d.mode(), since)) {
            if (r.outcomeR() != null && signals.position(r.strategyPositionId()).map(StrategyPosition::deploymentId).filter(d.id()::equals).isPresent()) {
                mine.add(r);
            }
        }
        return mine.subList(Math.max(0, mine.size() - properties.trailingTrades()), mine.size());
    }

    /** The first date of the {@code sessions} trading sessions ending at {@code to} (inclusive). */
    private LocalDate sessionsBack(LocalDate to, int sessions) {
        LocalDate date = to;
        int counted = clock.isTradingDay(date) ? 1 : 0;
        while (counted < sessions) {
            date = date.minusDays(1);
            if (clock.isTradingDay(date)) {
                counted++;
            }
        }
        return date;
    }

    // --- evaluation and actions ---

    /** Evaluates every enabled deployment (the sweep). */
    public void evaluateAll() {
        if (!properties.enabled()) {
            return;
        }
        for (StrategyDeployment d : strategies.deployments(null, null, true)) {
            try {
                evaluate(d.id());
            } catch (RuntimeException e) {
                log.warn("Drift evaluation of deployment {} failed", d.id(), e);
            }
        }
    }

    /** After a strategy trade was reviewed: re-evaluate its deployment. */
    public void onReview(TradeReview review) {
        if (!properties.enabled() || review.strategyPositionId() == null) {
            return;
        }
        signals.position(review.strategyPositionId()).map(StrategyPosition::deploymentId).ifPresent(this::evaluate);
    }

    /**
     * Assesses the deployment and stores the status. The status's configured actions run only when it is worse than the
     * worst status already acted on (so a pause fires once), the deployment is enabled and no override covers it; an
     * improvement lowers that watermark. A status change is audited ({@code STRATEGY_DRIFT_CHANGED}).
     */
    public DriftReport evaluate(UUID deploymentId) {
        return evaluate(deploymentId, properties::actionsFor);
    }

    /** {@link #evaluate(UUID)} with the actions per status supplied (tests exercise actions the shared config does not enable). */
    synchronized DriftReport evaluate(UUID deploymentId, java.util.function.Function<DriftStatus, List<DriftAction>> actionsFor) {
        StrategyDeployment d = strategies.deployment(deploymentId).orElseThrow(() -> new StrategyException.NotFound("Deployment " + deploymentId + " not found"));
        DriftReport report = report(d);
        Instant now = clock.now();
        Optional<DriftState> prev = store.state(d.id());
        DriftStatus acted = prev.map(DriftState::actedStatus).orElse(DriftStatus.INSUFFICIENT_DATA);
        List<DriftAction> toRun = List.of();
        List<String> results = new ArrayList<>();
        if (report.status().worseThan(acted)) {
            if (!d.enabled()) {
                results.add("no action: deployment is paused");
            } else if (prev.isPresent() && prev.get().overrides(report.status())) {
                results.add("no action: overridden by " + prev.get().overrideBy() + " (" + prev.get().overrideReason() + ")");
            } else {
                toRun = new ArrayList<>(actionsFor.apply(report.status()));
                // autonomy 5 manages its own lifecycle (plan M5.2): it pauses itself once drift reaches hejje.auto.self-pause-drift
                if (d.autonomyLevel() >= 5 && !DriftStatus.valueOf(auto.selfPauseDrift()).worseThan(report.status()) && !toRun.contains(DriftAction.PAUSE)) {
                    toRun.add(DriftAction.PAUSE);
                }
                acted = report.status();
                if (prev.isPresent() && prev.get().overrideStatus() != null) {
                    store.clearOverride(d.id()); // the status went past what was overridden
                }
            }
        } else if (acted.worseThan(report.status())) {
            acted = report.status();
        }
        if (report.status() == DriftStatus.HEALTHY && prev.isPresent() && prev.get().overrideStatus() != null) {
            store.clearOverride(d.id());
        }
        store.saveState(d.id(), d.versionId(), d.strategyId(), report.status(), acted, report.triggered(), now);
        for (DriftAction action : toRun) {
            try {
                results.add(apply(action, d, report));
            } catch (RuntimeException e) {
                log.warn("Drift action {} on deployment {} failed", action, d.id(), e);
                results.add(action + " failed: " + e.getMessage());
            }
        }
        boolean changed = prev.map(s -> s.status() != report.status()).orElse(true);
        if (changed || !results.isEmpty()) {
            store.insertAssessment(report, results, now);
        }
        if (changed) {
            Map<String, Object> payload = statistics(report);
            payload.put("from", prev.map(s -> s.status().name()).orElse("NONE"));
            payload.put("actions", results);
            audit.record(AuditEvent.of(AuditEventType.STRATEGY_DRIFT_CHANGED, ActorType.SYSTEM).withActorId(SYSTEM).withStrategyId(d.strategyId())
                    .withPayload(payload));
        }
        return report;
    }

    private String apply(DriftAction action, StrategyDeployment d, DriftReport report) {
        String reason = "drift " + report.status() + (report.triggered().isEmpty() ? "" : ": " + report.triggered().get(0));
        switch (action) {
            case ALERT -> {
                log.warn("Strategy drift {} on deployment {} ({} v{}, {}): {}", report.status(), d.id(), d.strategyId(), report.version(), d.mode(),
                        report.triggered());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("deploymentId", d.id().toString());
                data.put("strategyId", d.strategyId().toString());
                data.put("versionId", d.versionId().toString());
                data.put("status", report.status().name());
                data.put("triggered", report.triggered());
                events.publishEvent(new ClientNotification("drift", data));
                return "ALERT";
            }
            case LOWER_SCORE -> {
                scoring.recomputeVersion(d.versionId());
                return "LOWER_SCORE " + properties.pointsFor(report.status());
            }
            case REDUCE_SIZE -> {
                BigDecimal target = properties.sizeMultiplier();
                StrategyDeployment current = strategies.deployment(d.id()).orElse(d);
                if (current.sizeMultiplier().compareTo(target) <= 0) {
                    return "REDUCE_SIZE: already " + current.sizeMultiplier().toPlainString();
                }
                strategies.setSizeMultiplier(d.id(), target, reason, SYSTEM);
                return "REDUCE_SIZE " + current.sizeMultiplier().toPlainString() + " -> " + target.setScale(2).toPlainString();
            }
            case MOVE_TO_PAPER -> {
                if (d.mode().simulated()) {
                    return "MOVE_TO_PAPER skipped: already " + d.mode();
                }
                StrategyDeployment paper = strategies.moveToPaper(d.id(), reason, SYSTEM);
                recordPause(d, report, reason);
                return "MOVE_TO_PAPER -> deployment " + paper.id();
            }
            case PAUSE -> {
                if (!strategies.deployment(d.id()).map(StrategyDeployment::enabled).orElse(false)) {
                    return "PAUSE: already paused";
                }
                strategies.updateDeployment(d.id(), false, reason, SYSTEM);
                recordPause(d, report, reason);
                return "PAUSE";
            }
        }
        throw new IllegalStateException("unhandled " + action);
    }

    private void recordPause(StrategyDeployment d, DriftReport report, String reason) {
        Map<String, Object> payload = statistics(report);
        payload.put("reason", reason);
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_PAUSED, ActorType.SYSTEM).withActorId(SYSTEM).withStrategyId(d.strategyId()).withPayload(payload));
    }

    private static Map<String, Object> statistics(DriftReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deploymentId", r.deploymentId().toString());
        m.put("versionId", r.versionId().toString());
        m.put("mode", r.mode());
        m.put("status", r.status().name());
        m.put("trades", r.live().trades());
        m.put("winRate", round(r.live().winRate()));
        m.put("expectancyR", round(r.live().expectancyR()));
        m.put("maxDrawdownR", round(r.live().maxDrawdownR()));
        if (r.backtest() != null) {
            m.put("backtestId", r.backtest().backtestId().toString());
            m.put("backtestWinRate", round(r.backtest().winRate()));
            m.put("backtestExpectancyR", round(r.backtest().expectancyR()));
            m.put("backtestMaxDrawdownR", round(r.backtest().maxDrawdownR()));
        }
        if (r.stats() != null) {
            m.put("winRatePValue", round(r.stats().winRatePValue()));
            m.put("expectancyInterval", List.of(round(r.stats().expectancyLow()), round(r.stats().expectancyHigh())));
            if (r.stats().drawdownMultiple() != null) {
                m.put("drawdownMultiple", round(r.stats().drawdownMultiple()));
            }
        }
        m.put("triggered", r.triggered());
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    // --- override and views ---

    /**
     * Manual override (with a reason): actions stop for the current status and anything no worse; the size multiplier
     * goes back to 1.00. A paused deployment stays paused (re-enable it through the deployment update).
     */
    public synchronized DriftState override(UUID deploymentId, String reason, String by) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to override drift");
        }
        StrategyDeployment d = strategies.deployment(deploymentId).orElseThrow(() -> new StrategyException.NotFound("Deployment " + deploymentId + " not found"));
        DriftState state = store.state(deploymentId).orElseThrow(() -> new StrategyException.Conflict("Deployment " + deploymentId + " has no drift assessment"));
        if (!state.status().worseThan(DriftStatus.HEALTHY)) {
            throw new StrategyException.Conflict("Nothing to override: drift status is " + state.status());
        }
        store.setOverride(deploymentId, state.status(), reason, by, clock.now());
        if (d.sizeMultiplier().compareTo(BigDecimal.ONE) < 0) {
            strategies.setSizeMultiplier(deploymentId, BigDecimal.ONE, "drift override: " + reason, by);
        }
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_DRIFT_OVERRIDDEN, ActorType.USER).withActorId(by).withStrategyId(d.strategyId())
                .withPayload(Map.of("deploymentId", deploymentId.toString(), "status", state.status().name(), "reason", reason)));
        try {
            scoring.recomputeVersion(d.versionId());
        } catch (RuntimeException e) {
            log.warn("Rescoring after a drift override failed", e);
        }
        return store.state(deploymentId).orElseThrow();
    }

    /** Every deployment of every version of the strategy, newest version first. */
    public List<DeploymentDrift> forStrategy(UUID strategyId) {
        List<DeploymentDrift> out = new ArrayList<>();
        List<StrategyVersion> versions = new ArrayList<>(strategies.versions(strategyId));
        versions.sort((a, b) -> Integer.compare(b.version(), a.version()));
        for (StrategyVersion v : versions) {
            for (StrategyDeployment d : strategies.deployments(v.id(), null, null)) {
                out.add(new DeploymentDrift(report(d), store.state(d.id()).orElse(null), store.history(d.id(), 5)));
            }
        }
        return out;
    }

    public Optional<DriftState> state(UUID deploymentId) {
        return store.state(deploymentId);
    }
}
