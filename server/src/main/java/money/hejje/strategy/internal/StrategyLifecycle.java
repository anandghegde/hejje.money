package money.hejje.strategy.internal;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.strategy.StrategyEvidence;
import money.hejje.strategy.VersionStatus;
import org.springframework.stereotype.Component;

/**
 * Version status transitions (PRD section 25, plan M2.1):
 * {@code DRAFT -> BACKTESTED -> VALIDATED -> PAPER -> LIVE}; {@code PAUSED} from PAPER/LIVE and back; {@code RETIRED}
 * from anywhere. Forward moves need evidence from the backtest module.
 */
@Component
public class StrategyLifecycle {

    private static final Map<VersionStatus, Set<VersionStatus>> ALLOWED = new EnumMap<>(VersionStatus.class);

    static {
        ALLOWED.put(VersionStatus.DRAFT, EnumSet.of(VersionStatus.BACKTESTED, VersionStatus.RETIRED));
        ALLOWED.put(VersionStatus.BACKTESTED, EnumSet.of(VersionStatus.VALIDATED, VersionStatus.RETIRED));
        ALLOWED.put(VersionStatus.VALIDATED, EnumSet.of(VersionStatus.PAPER, VersionStatus.RETIRED));
        ALLOWED.put(VersionStatus.PAPER, EnumSet.of(VersionStatus.LIVE, VersionStatus.PAUSED, VersionStatus.RETIRED));
        ALLOWED.put(VersionStatus.LIVE, EnumSet.of(VersionStatus.PAUSED, VersionStatus.RETIRED));
        ALLOWED.put(VersionStatus.PAUSED, EnumSet.of(VersionStatus.PAPER, VersionStatus.LIVE, VersionStatus.RETIRED));
        ALLOWED.put(VersionStatus.RETIRED, EnumSet.noneOf(VersionStatus.class));
    }

    private final StrategyEvidence evidence;
    private final org.springframework.beans.factory.ObjectProvider<money.hejje.strategy.OptionsPaperEvidence> options;

    @org.springframework.beans.factory.annotation.Autowired
    StrategyLifecycle(StrategyEvidence evidence, org.springframework.beans.factory.ObjectProvider<money.hejje.strategy.OptionsPaperEvidence> options) {
        this.evidence = evidence;
        this.options = options;
    }

    StrategyLifecycle(StrategyEvidence evidence) {
        this(evidence, null);
    }

    /**
     * {@link #reject(UUID, VersionStatus, VersionStatus)} for a version, with the options rules (plan M5.4): the
     * historical store has no option candles, so an options strategy goes DRAFT → PAPER directly, cannot become
     * BACKTESTED or VALIDATED, and needs closed paper options positions before LIVE.
     */
    public String reject(money.hejje.strategy.StrategyVersion version, VersionStatus to) {
        VersionStatus from = version.status();
        if (version.definition().legs().isEmpty()) {
            return reject(version.id(), from, to);
        }
        if (from == to) {
            return "version is already " + to;
        }
        if (to == VersionStatus.BACKTESTED || to == VersionStatus.VALIDATED) {
            return "an options strategy cannot be backtested (the historical store has no option candles); move it from DRAFT to PAPER";
        }
        if (from == VersionStatus.DRAFT && to == VersionStatus.PAPER) {
            return null;
        }
        if (!isTransitionAllowed(from, to)) {
            return "transition " + from + " -> " + to + " is not allowed";
        }
        if (to == VersionStatus.LIVE) {
            money.hejje.strategy.OptionsPaperEvidence paper = options == null ? null : options.getIfAvailable();
            int closed = paper == null ? 0 : paper.closedPaperPositions(version.id());
            int required = paper == null ? 30 : paper.requiredPaperPositions();
            if (closed < required) {
                return "an options strategy needs " + required + " closed paper options positions before LIVE (it has " + closed + ")";
            }
        }
        return null;
    }

    public static boolean isTransitionAllowed(VersionStatus from, VersionStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** Checks the transition; returns null when allowed, otherwise the reason it is not. */
    public String reject(UUID versionId, VersionStatus from, VersionStatus to) {
        if (from == to) {
            return "version is already " + to;
        }
        if (!isTransitionAllowed(from, to)) {
            return "transition " + from + " -> " + to + " is not allowed";
        }
        if (to == VersionStatus.BACKTESTED && !evidence.hasBacktest(versionId)) {
            return "a completed backtest is required before BACKTESTED";
        }
        if (to == VersionStatus.VALIDATED && !evidence.hasValidatedBacktest(versionId)) {
            return "a completed out-of-sample or walk-forward backtest that passes the minimum-trade rule is required before VALIDATED";
        }
        return null;
    }
}
