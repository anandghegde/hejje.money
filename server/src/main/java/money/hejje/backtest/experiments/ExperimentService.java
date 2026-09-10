package money.hejje.backtest.experiments;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestResult;
import money.hejje.backtest.BacktestService;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.Split;
import money.hejje.backtest.Splits;
import money.hejje.backtest.WalkForwardWindow;
import money.hejje.backtest.experiments.internal.ExperimentStore;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.ValidationReport;
import money.hejje.strategy.dsl.Condition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Runs experiments (plan M4.7): validates every variant's delta against the base definition, backtests the valid ones
 * and the baseline on the same dataset and split with bounded parallelism, ranks them with {@link ExperimentRanking},
 * and promotes a chosen variant into a normal DRAFT version. Base versions and statuses are never touched.
 */
@Service
public class ExperimentService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);

    public record DatasetRequest(LocalDate from, LocalDate to, List<UUID> instrumentIds, Splits splits) {}

    private final StrategyService strategies;
    private final BacktestService backtests;
    private final ExperimentStore store;
    private final AuditService audit;
    private final HejjeClock clock;
    private final ExperimentProperties props;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> daemon(r, "experiment-coordinator"));
    private final ExecutorService pool;

    ExperimentService(StrategyService strategies, BacktestService backtests, ExperimentStore store, AuditService audit, HejjeClock clock,
            ExperimentProperties props) {
        this.strategies = strategies;
        this.backtests = backtests;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.props = props;
        this.pool = Executors.newFixedThreadPool(Math.max(1, props.parallelism()), r -> daemon(r, "experiment-variant"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Experiments left QUEUED or RUNNING by a restart are marked FAILED (like backtests); rerun them instead. */
    @EventListener(ApplicationReadyEvent.class)
    void failLeftovers() {
        int n = store.failUnfinished("interrupted by a restart", clock.now());
        if (n > 0) {
            log.warn("Marked {} unfinished experiment(s) FAILED after restart", n);
        }
    }

    public VariantPreview preview(UUID baseVersionId, Map<String, Object> delta) {
        return preview(requireVersion(baseVersionId), delta);
    }

    private VariantPreview preview(StrategyVersion base, Map<String, Object> delta) {
        String yaml;
        try {
            yaml = strategies.writeTree(VariantDelta.apply(strategies.readTree(base.definitionYaml()), delta));
        } catch (IllegalArgumentException e) {
            return new VariantPreview(false, List.of("delta: " + e.getMessage()), null, List.of(), 0, 0);
        }
        ValidationReport report = strategies.validate(yaml);
        if (!report.valid()) {
            return new VariantPreview(false, report.errors().stream().map(e -> e.path() + ": " + e.message()).toList(), yaml, List.of(), 0, 0);
        }
        StrategyDefinition d = report.definition();
        int conditions = d.entry().conditions().size() + (d.exit() == null ? 0 : d.exit().conditions().size());
        return new VariantPreview(true, List.of(), yaml, d.entry().conditions().stream().map(Condition::text).toList(),
                VariantDelta.numbers(strategies.readTree(yaml)).size(), conditions);
    }

    /** Creates the experiment (baseline + variants) and starts it in the background. */
    public Experiment start(UUID baseVersionId, String goal, List<VariantSpec> variants, DatasetRequest request, String createdBy, UUID sessionId) {
        StrategyVersion base = requireVersion(baseVersionId);
        if (variants == null || variants.isEmpty() || variants.size() > props.maxVariants()) {
            throw new IllegalArgumentException("Give between 1 and " + props.maxVariants() + " variants");
        }
        Set<String> names = new HashSet<>(List.of("baseline"));
        for (VariantSpec v : variants) {
            if (v.name() == null || v.name().isBlank() || !names.add(v.name().trim())) {
                throw new IllegalArgumentException("Variant names must be unique, non-blank and not 'baseline': " + v.name());
            }
        }
        Optional<BacktestSpec> baseSpec = backtests.baseBacktest(baseVersionId).map(Backtest::spec);
        LocalDate to = request != null && request.to() != null ? request.to() : baseSpec.map(BacktestSpec::to).orElse(clock.today());
        LocalDate from = request != null && request.from() != null ? request.from() : baseSpec.map(BacktestSpec::from).orElse(to.minusYears(1));
        List<UUID> instruments = request != null && request.instrumentIds() != null && !request.instrumentIds().isEmpty() ? request.instrumentIds()
                : baseSpec.map(BacktestSpec::instrumentIds).orElse(List.of());
        Splits splits = request != null && request.splits() != null ? request.splits() : baseSpec.map(BacktestSpec::splits).orElse(Splits.DEFAULT_FIXED);
        Dataset dataset = new Dataset(instruments, from, to, baseSpec.map(BacktestSpec::timeframe).orElse(null), baseSpec.map(BacktestSpec::slippageBps).orElse(5));

        UUID id = Ids.newId();
        List<Variant> rows = new ArrayList<>();
        rows.add(variantRow(id, 0, "baseline", "the base version as is", Map.of(), preview(base, Map.of())));
        for (int i = 0; i < variants.size(); i++) {
            VariantSpec v = variants.get(i);
            rows.add(variantRow(id, i + 1, v.name().trim(), v.description(), v.delta(), preview(base, v.delta())));
        }
        Experiment e = new Experiment(id, base.id(), base.strategyId(), goal, dataset, splits, ExperimentStatus.QUEUED, createdBy, sessionId, clock.now(), null, null,
                List.of(), rows);
        store.insert(e);
        audit.record(AuditEvent.of(AuditEventType.EXPERIMENT_STARTED, sessionId == null ? ActorType.USER : ActorType.AGENT).withActorId(createdBy)
                .withStrategyId(base.strategyId()).withPayload(Map.of("experimentId", id.toString(), "baseVersionId", base.id().toString(), "variants", variants.size())));
        coordinator.submit(() -> execute(id));
        return store.find(id).orElseThrow();
    }

    private Variant variantRow(UUID experimentId, int ordinal, String name, String description, Map<String, Object> delta, VariantPreview p) {
        return new Variant(Ids.newId(), experimentId, ordinal, name, description, delta, p.yaml(), p.valid() ? VariantStatus.QUEUED : VariantStatus.INVALID, null, null,
                null, null, List.of(), p.parameterCount(), p.conditionCount(), p.valid() ? null : String.join("; ", p.errors()), null);
    }

    /** Backtests the queued variants (bounded parallelism) and ranks the finished ones. */
    void execute(UUID id) {
        Experiment e = store.find(id).orElse(null);
        if (e == null || e.status() != ExperimentStatus.QUEUED) {
            return;
        }
        store.markRunning(id);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Variant v : e.variants()) {
                if (v.status() != VariantStatus.QUEUED) {
                    continue;
                }
                tasks.add(() -> {
                    runVariant(e, v);
                    return null;
                });
            }
            pool.invokeAll(tasks);
            List<Variant> done = store.find(id).orElseThrow().variants().stream().filter(v -> v.status() == VariantStatus.DONE).toList();
            List<ExperimentRanking.Candidate> candidates = done.stream().map(v -> new ExperimentRanking.Candidate(v.name(), v.baseline(), v.metrics(),
                    v.parameterCount(), v.conditionCount(), VariantDelta.numbers(strategies.readTree(v.definitionYaml())))).toList();
            ExperimentRanking.Result result = ExperimentRanking.rank(candidates);
            for (ExperimentRanking.Ranked r : result.variants()) {
                store.rankVariant(id, r.name(), r.rank(), r.score(), r.verdict(), r.warnings());
            }
            store.finish(id, ExperimentStatus.DONE, result.notes(), null, clock.now());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            store.finish(id, ExperimentStatus.FAILED, List.of(), "interrupted", clock.now());
        } catch (RuntimeException ex) {
            log.warn("Experiment {} failed", id, ex);
            store.finish(id, ExperimentStatus.FAILED, List.of(), ex.getMessage(), clock.now());
        }
        Experiment finished = store.find(id).orElseThrow();
        audit.record(AuditEvent.of(AuditEventType.EXPERIMENT_FINISHED, ActorType.SYSTEM).withActorId("experiments").withStrategyId(finished.strategyId())
                .withPayload(Map.of("experimentId", id.toString(), "status", finished.status().name())));
    }

    private void runVariant(Experiment e, Variant v) {
        try {
            StrategyDefinition definition = strategies.parse(v.definitionYaml());
            Dataset d = e.dataset();
            BacktestSpec spec = new BacktestSpec(e.baseVersionId(), d.instrumentIds(), d.timeframe(), d.from(), d.to(), null, d.slippageBps(), null, e.splits(), null,
                    null);
            store.completeVariant(v.id(), metrics(backtests.evaluate(spec, definition)));
        } catch (RuntimeException ex) {
            store.failVariant(v.id(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    static VariantMetrics metrics(BacktestResult r) {
        List<WalkForwardWindow> windows = r.windows() == null ? List.of() : r.windows();
        Double std = null;
        if (windows.size() >= 2) {
            double mean = windows.stream().mapToDouble(WalkForwardWindow::expectancyR).average().orElse(0);
            std = Math.sqrt(windows.stream().mapToDouble(w -> Math.pow(w.expectancyR() - mean, 2)).sum() / (windows.size() - 1));
        }
        Map<Split, BacktestMetrics> by = r.bySplit() == null ? Map.of() : r.bySplit();
        return new VariantMetrics(summary(r.overall()), summary(by.get(Split.IN_SAMPLE)), summary(by.get(Split.VALIDATION)), summary(by.get(Split.OUT_OF_SAMPLE)), std,
                windows.size(), r.warnings().stream().map(w -> w.severity() + " " + w.code()).toList(), r.resultHash());
    }

    private static SplitSummary summary(BacktestMetrics m) {
        return m == null ? null : new SplitSummary(m.totalTrades(), m.expectancyR(), m.profitFactor(), m.maxDrawdownR(), m.winRate(), m.netPnl().toRupees());
    }

    public Optional<Experiment> get(UUID id) {
        return store.find(id);
    }

    public List<Experiment> list(UUID baseVersionId, int limit) {
        return store.list(baseVersionId, Math.max(1, Math.min(limit, 100)));
    }

    /** A finished variant becomes the strategy's next version (DRAFT, through the normal lifecycle). */
    public StrategyVersion promote(UUID experimentId, UUID variantId, String by) {
        Experiment e = store.find(experimentId).orElseThrow(() -> new NoSuchElementException("Experiment " + experimentId + " not found"));
        Variant v = e.variants().stream().filter(x -> x.id().equals(variantId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Variant " + variantId + " not found"));
        if (v.baseline() || v.status() != VariantStatus.DONE || v.promotedVersionId() != null) {
            throw new IllegalStateException("Only a finished, not yet promoted, non-baseline variant can be promoted");
        }
        StrategyVersion version = strategies.addVersion(e.strategyId(), v.definitionYaml(), "promoted from experiment " + experimentId.toString().substring(0, 8)
                + " variant " + v.name(), by);
        store.promoted(variantId, version.id());
        return version;
    }

    private StrategyVersion requireVersion(UUID id) {
        return strategies.versionById(id).orElseThrow(() -> new NoSuchElementException("Strategy version " + id + " not found"));
    }

}
