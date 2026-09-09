package money.hejje.strategy;

import java.util.UUID;

/**
 * What the lifecycle needs to know about backtests without depending on the backtest module. The backtest module
 * (M2.3) provides the real bean; until then a default bean reports no evidence, so no version can leave DRAFT.
 */
public interface StrategyEvidence {

    /** A completed backtest exists for the version (DRAFT to BACKTESTED). */
    boolean hasBacktest(UUID versionId);

    /** A completed out-of-sample or walk-forward backtest exists and passes the minimum-trade rule (to VALIDATED). */
    boolean hasValidatedBacktest(UUID versionId);
}
