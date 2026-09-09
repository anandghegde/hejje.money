package money.hejje.scoring;

import java.util.Optional;
import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.strategy.StrategyVersion;

/** What adjusters see: the version, the instrument being scored and the base backtest (if any). */
public record ScoreContext(StrategyVersion version, UUID instrumentId, Optional<Backtest> baseBacktest) {
}
