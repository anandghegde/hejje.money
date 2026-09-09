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

    StrategyLifecycle(StrategyEvidence evidence) {
        this.evidence = evidence;
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
