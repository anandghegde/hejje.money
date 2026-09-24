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
 * @param sizeFactors  plan M9.7: the risk-event size cut per session that has one (sessions not listed size at 1)
 */
public record BacktestInput(StrategyDefinition definition, BacktestSpec spec, Map<UUID, InstrumentMeta> instruments,
        Map<UUID, List<Candle>> candles, Money riskPerTrade, Map<java.time.LocalDate, java.math.BigDecimal> sizeFactors) {

    public BacktestInput(StrategyDefinition definition, BacktestSpec spec, Map<UUID, InstrumentMeta> instruments, Map<UUID, List<Candle>> candles,
            Money riskPerTrade) {
        this(definition, spec, instruments, candles, riskPerTrade, Map.of());
    }

    public BacktestInput {
        instruments = Map.copyOf(instruments);
        candles = Map.copyOf(candles);
        sizeFactors = sizeFactors == null ? Map.of() : Map.copyOf(sizeFactors);
        if (riskPerTrade == null || riskPerTrade.paise() <= 0) {
            throw new IllegalArgumentException("riskPerTrade must be positive");
        }
    }
}
