package money.hejje.scoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestService;
import money.hejje.common.Ids;
import money.hejje.common.time.HejjeClock;
import money.hejje.scoring.internal.BaseScoreCalculator;
import money.hejje.scoring.internal.Comparisons;
import money.hejje.scoring.internal.ScoreStore;
import money.hejje.scoring.internal.SlippageSensitivity;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.springframework.stereotype.Service;

/** Public API of the scoring module: compute, read and compare Hejje Scores. Formula: docs/hejje-score.md. */
@Service
public class ScoringService {

    private final ScoreStore store;
    private final BacktestService backtests;
    private final StrategyService strategies;
    private final SlippageSensitivity sensitivity;
    private final List<ScoreAdjuster> adjusters;
    private final HejjeClock clock;

    ScoringService(ScoreStore store, BacktestService backtests, StrategyService strategies, SlippageSensitivity sensitivity,
            List<ScoreAdjuster> adjusters, HejjeClock clock) {
        this.store = store;
        this.backtests = backtests;
        this.strategies = strategies;
        this.sensitivity = sensitivity;
        this.adjusters = adjusters;
        this.clock = clock;
    }

    /** Computes and stores the score of one version on one instrument. */
    public ScoreBreakdown compute(UUID versionId, UUID instrumentId) {
        StrategyVersion version = strategies.versionById(versionId)
                .orElseThrow(() -> new StrategyException.NotFound("Strategy version " + versionId + " not found"));
        Optional<Backtest> base = backtests.baseBacktest(versionId);
        BaseScoreCalculator.BaseScore baseScore = base.map(b -> BaseScoreCalculator.compute(b, sensitivity.of(b)))
                .orElseGet(BaseScoreCalculator::none);
        ScoreContext context = new ScoreContext(version, instrumentId, base);
        List<Adjustment> adjustments = new ArrayList<>();
        for (ScoreAdjuster adjuster : adjusters) {
            adjustments.add(adjuster.adjust(context));
        }
        int total = adjustments.stream().mapToInt(Adjustment::delta).sum();
        int finalScore = (int) Math.max(0, Math.min(100, Math.round(baseScore.base()) + total));
        ScoreBreakdown breakdown = new ScoreBreakdown(Ids.newId(), versionId, instrumentId, clock.now(), base.map(Backtest::id).orElse(null),
                baseScore.base(), baseScore.cap(), baseScore.components(), adjustments, finalScore);
        store.insert(breakdown);
        return breakdown;
    }

    /** Recomputes the version's score on every instrument it is deployed on, or on its resolved universe. */
    public List<ScoreBreakdown> recomputeVersion(UUID versionId) {
        StrategyVersion version = strategies.versionById(versionId)
                .orElseThrow(() -> new StrategyException.NotFound("Strategy version " + versionId + " not found"));
        List<ScoreBreakdown> out = new ArrayList<>();
        for (UUID instrumentId : instrumentsFor(version)) {
            out.add(compute(versionId, instrumentId));
        }
        if (out.isEmpty()) {
            out.add(compute(versionId, null));
        }
        return out;
    }

    /** Deployment instruments first, then the resolved universe; empty when neither resolves. */
    public List<UUID> instrumentsFor(StrategyVersion version) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (StrategyDeployment d : strategies.deployments(version.id(), null, null)) {
            ids.addAll(d.instrumentIds());
        }
        if (ids.isEmpty()) {
            strategies.resolveUniverse(version.definition()).forEach(i -> ids.add(i.id()));
        }
        return new ArrayList<>(ids);
    }

    public Optional<ScoreBreakdown> latest(UUID versionId, UUID instrumentId) {
        return store.latest(versionId, instrumentId);
    }

    /** The latest score per instrument for a version. */
    public List<ScoreBreakdown> latestForVersion(UUID versionId) {
        return store.latestForVersion(versionId);
    }

    /** The headline score of a version: the best latest instrument score, or empty when never scored. */
    public Optional<Integer> headline(UUID versionId) {
        return store.latestForVersion(versionId).stream().map(ScoreBreakdown::finalScore).max(Integer::compare);
    }

    // --- comparison (PRD 11, 21) ---

    public List<ComparisonRow> compare(List<UUID> versionIds) {
        List<ComparisonRow> rows = new ArrayList<>();
        for (UUID versionId : versionIds) {
            rows.add(row(versionId));
        }
        return rows;
    }

    public VersionComparison compareVersions(UUID strategyId, int a, int b) {
        StrategyVersion va = strategies.version(strategyId, a).orElseThrow(() -> new StrategyException.NotFound("Version " + a + " not found"));
        StrategyVersion vb = strategies.version(strategyId, b).orElseThrow(() -> new StrategyException.NotFound("Version " + b + " not found"));
        ComparisonRow ra = row(va.id());
        ComparisonRow rb = row(vb.id());
        return Comparisons.versions(ra, rb);
    }

    ComparisonRow row(UUID versionId) {
        StrategyVersion version = strategies.versionById(versionId)
                .orElseThrow(() -> new StrategyException.NotFound("Strategy version " + versionId + " not found"));
        Strategy strategy = strategies.find(version.strategyId()).orElseThrow();
        Optional<Backtest> base = backtests.baseBacktest(versionId);
        return Comparisons.row(strategy, version, base, headline(versionId).orElse(null));
    }

    /** Snapshot of the latest scores keyed by instrument (for callers such as the Today screen). */
    public Map<UUID, ScoreBreakdown> latestByInstrument(UUID versionId) {
        Map<UUID, ScoreBreakdown> out = new LinkedHashMap<>();
        for (ScoreBreakdown s : store.latestForVersion(versionId)) {
            out.put(s.instrumentId(), s);
        }
        return out;
    }
}
