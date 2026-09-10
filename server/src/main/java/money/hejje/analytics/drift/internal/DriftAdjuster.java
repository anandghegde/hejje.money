package money.hejje.analytics.drift.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import money.hejje.analytics.drift.DriftAction;
import money.hejje.analytics.drift.DriftProperties;
import money.hejje.analytics.drift.DriftState;
import money.hejje.analytics.drift.DriftStatus;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreAdjuster;
import money.hejje.scoring.ScoreContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The drift monitor's LOWER_SCORE action (plan M5.1): the worst stored drift status across the version's deployments
 * lowers the score by that status's points (−15..0) when its configured actions include LOWER_SCORE and no override
 * covers it. Reads the stored state, so the score changes only when an evaluation met a threshold.
 */
@Component
@Order(50)
class DriftAdjuster implements ScoreAdjuster {

    private final DriftStore store;
    private final DriftProperties properties;

    DriftAdjuster(DriftStore store, DriftProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "Live-vs-backtest drift";
    }

    @Override
    public int min() {
        return -15;
    }

    @Override
    public int max() {
        return 0;
    }

    @Override
    public Adjustment adjust(ScoreContext ctx) {
        if (!properties.enabled()) {
            return Adjustment.none(name(), min(), max(), "drift monitor disabled");
        }
        Optional<DriftState> worst = store.statesForVersion(ctx.version().id()).stream().max(Comparator.comparing(DriftState::status));
        if (worst.isEmpty() || !worst.get().status().worseThan(DriftStatus.HEALTHY)) {
            return Adjustment.none(name(), min(), max(), worst.map(s -> s.status() == DriftStatus.HEALTHY
                    ? "paper/live trades in line with the backtest" : "not enough paper/live trades to assess drift").orElse("no drift assessment yet"));
        }
        DriftState s = worst.get();
        if (s.overrides(s.status())) {
            return Adjustment.none(name(), min(), max(), "drift " + s.status() + " overridden by " + s.overrideBy() + ": " + s.overrideReason());
        }
        if (!properties.actionsFor(s.status()).contains(DriftAction.LOWER_SCORE)) {
            return Adjustment.none(name(), min(), max(), "drift " + s.status() + " is configured not to lower the score");
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("drift " + s.status() + " on deployment " + s.deploymentId());
        evidence.addAll(s.triggered());
        return new Adjustment(name(), properties.pointsFor(s.status()), min(), max(), evidence);
    }
}
