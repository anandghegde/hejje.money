package money.hejje.analytics.drift;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Drift monitor settings ({@code hejje.drift.*}, config/drift.yaml, docs/analytics.md).
 *
 * @param trailingTrades   at most this many of the newest closed trades are compared
 * @param trailingSessions ... and only those closed within this many trading sessions
 * @param minTrades        below this many trades the status is INSUFFICIENT_DATA
 * @param bootstrapSamples resamples for the expectancy confidence interval (fixed seed: deterministic)
 * @param confidence       two-sided confidence of that interval
 * @param sizeMultiplier   what REDUCE_SIZE sets the deployment's size multiplier to
 * @param actions          actions per status (WATCH, DEGRADING, FAILED)
 * @param scorePoints      score delta per status applied by the adjuster when the status's actions include LOWER_SCORE
 */
@ConfigurationProperties("hejje.drift")
public record DriftProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT10M") Duration interval,
        @DefaultValue("30") int trailingTrades,
        @DefaultValue("60") int trailingSessions,
        @DefaultValue("10") int minTrades,
        @DefaultValue("2000") int bootstrapSamples,
        @DefaultValue("0.90") double confidence,
        @DefaultValue("0.50") BigDecimal sizeMultiplier,
        Thresholds thresholds,
        Map<DriftStatus, List<DriftAction>> actions,
        Map<DriftStatus, Integer> scorePoints) {

    public DriftProperties {
        thresholds = thresholds == null ? new Thresholds(null, null, null) : thresholds;
        actions = actions == null || actions.isEmpty() ? Map.of(
                DriftStatus.WATCH, List.of(DriftAction.ALERT, DriftAction.LOWER_SCORE),
                DriftStatus.DEGRADING, List.of(DriftAction.ALERT, DriftAction.LOWER_SCORE, DriftAction.REDUCE_SIZE),
                DriftStatus.FAILED, List.of(DriftAction.ALERT, DriftAction.LOWER_SCORE, DriftAction.PAUSE)) : Map.copyOf(actions);
        scorePoints = scorePoints == null || scorePoints.isEmpty()
                ? Map.of(DriftStatus.WATCH, -5, DriftStatus.DEGRADING, -10, DriftStatus.FAILED, -15) : Map.copyOf(scorePoints);
    }

    public List<DriftAction> actionsFor(DriftStatus status) {
        return actions.getOrDefault(status, List.of());
    }

    public int pointsFor(DriftStatus status) {
        return Math.max(-15, Math.min(0, scorePoints.getOrDefault(status, 0)));
    }

    /** Criteria per level; a level is reached when any configured (non-null) criterion is met. */
    public record Thresholds(Level watch, Level degrading, Level failed) {

        public Thresholds {
            watch = watch == null ? new Level(0.20, 0.50, null, null, 1.0) : watch;
            degrading = degrading == null ? new Level(0.05, null, 1.0, null, 1.5) : degrading;
            failed = failed == null ? new Level(null, null, null, 0.0, 2.0) : failed;
        }
    }

    /**
     * @param winRatePValue        binomial P(wins ≤ observed | backtest win rate) below this
     * @param expectancyRatio      live mean R below this fraction of the backtest expectancy (point estimate)
     * @param expectancyUpperRatio upper confidence bound of the live expectancy below this fraction of the backtest's
     * @param expectancyUpperR     upper confidence bound of the live expectancy below this many R (0.0: losing with confidence)
     * @param drawdownMultiple     live max drawdown (R) at or above this multiple of the backtest's
     */
    public record Level(Double winRatePValue, Double expectancyRatio, Double expectancyUpperRatio, Double expectancyUpperR, Double drawdownMultiple) {
    }
}
