package money.hejje.backtest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Money;
import money.hejje.common.Timeframe;

/**
 * What to backtest (plan M2.3).
 *
 * @param versionId       strategy version
 * @param instrumentIds   instruments to replay; empty = the definition's resolved universe
 * @param timeframe       candle timeframe; null = the definition's
 * @param from            first session (IST date, inclusive)
 * @param to              last session (inclusive)
 * @param fillModel       NEXT_OPEN (default) or BAR_CLOSE
 * @param slippageBps     slippage applied against the trade on market/stop fills (default 5)
 * @param costModelVersion cost model tag recorded with the result (informational; the current model is always used)
 * @param splits          split scheme (default FIXED 60/20/20)
 * @param initialCapital  capital for return, Sharpe and percent-of-capital sizing (default 10,00,000)
 * @param riskPerTrade    money risked per trade; null = the definition's position sizing, else the module default
 */
public record BacktestSpec(UUID versionId, List<UUID> instrumentIds, Timeframe timeframe, LocalDate from, LocalDate to,
        FillModel fillModel, int slippageBps, String costModelVersion, Splits splits, Money initialCapital, Money riskPerTrade) {

    public BacktestSpec {
        if (versionId == null) {
            throw new IllegalArgumentException("versionId is required");
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("from/to must be a valid date range");
        }
        instrumentIds = instrumentIds == null ? List.of() : List.copyOf(instrumentIds);
        fillModel = fillModel == null ? FillModel.NEXT_OPEN : fillModel;
        if (slippageBps < 0) {
            throw new IllegalArgumentException("slippageBps must not be negative");
        }
        splits = splits == null ? Splits.DEFAULT_FIXED : splits;
        initialCapital = initialCapital == null ? Money.ofRupees(1_000_000) : initialCapital;
        if (initialCapital.paise() <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive");
        }
    }
}
