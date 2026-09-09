package money.hejje.backtest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Money;
import money.hejje.market.Candle;
import money.hejje.strategy.StrategyDefinition;

/**
 * Everything the engine needs, already loaded: the definition, the spec, instrument facts and candles per instrument
 * (oldest first; candles before {@code spec.from()} only warm indicators up).
 *
 * @param riskPerTrade money risked per trade after resolving spec, definition and defaults
 */
public record BacktestInput(StrategyDefinition definition, BacktestSpec spec, Map<UUID, InstrumentMeta> instruments,
        Map<UUID, List<Candle>> candles, Money riskPerTrade) {

    public BacktestInput {
        instruments = Map.copyOf(instruments);
        candles = Map.copyOf(candles);
        if (riskPerTrade == null || riskPerTrade.paise() <= 0) {
            throw new IllegalArgumentException("riskPerTrade must be positive");
        }
    }
}
