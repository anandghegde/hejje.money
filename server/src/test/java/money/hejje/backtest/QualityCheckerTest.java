package money.hejje.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import money.hejje.backtest.internal.QualityChecker;
import money.hejje.common.Money;
import money.hejje.common.Timeframe;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.internal.DefinitionParser;
import org.junit.jupiter.api.Test;

class QualityCheckerTest {

    final StrategyDefinition def = new DefinitionParser().parse(BacktestEngineTest.ORB.replace("- close > opening_range_high",
            "- close > opening_range_high\n    - relative_volume(20) > 1.4\n    - ema(9) > ema(21)"));

    @Test
    void twentyTradesTripTheSampleAndConcentrationWarnings() {
        List<BacktestTrade> trades = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            trades.add(MetricsCalculatorTest.trade(LocalDate.of(2026, 9, 1).plusDays(i), 10, i == 0 ? 5000 : 100, i == 0 ? 5 : 0.1));
        }
        BacktestSpec spec = new BacktestSpec(java.util.UUID.randomUUID(), List.of(), Timeframe.M5, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                FillModel.NEXT_OPEN, 5, null, Splits.NONE, Money.ofRupees(100_000), null);
        List<QualityWarning> warnings = new QualityChecker().check(def, spec, trades, Map.of(), 22, 20, Map.of(), Map.of());
        assertThat(warnings).extracting(QualityWarning::code).contains("INSUFFICIENT_SAMPLE", "CONCENTRATED", "MISSING_DATA", "OVERFIT_RISK");
        assertThat(warnings).filteredOn(w -> w.code().equals("INSUFFICIENT_SAMPLE")).extracting(QualityWarning::severity)
                .containsExactly(QualityWarning.Severity.FAIL);
        // 20 trades / 6 parameters (15m, 20, 1.4, 9, 21 + target 2) = 3.3 per parameter
        assertThat(QualityChecker.parameterCount(def)).isEqualTo(6);
    }

    @Test
    void rulesUsingMicrostructureAreFlaggedAsNeverPassingOnHistory() {
        StrategyDefinition micro = new DefinitionParser().parse(BacktestEngineTest.ORB.replace("- close > opening_range_high",
                "- close > opening_range_high\n    - book_imbalance > 0.2\n    - flow_up_share(5) > 0.6"));
        BacktestSpec spec = new BacktestSpec(java.util.UUID.randomUUID(), List.of(), Timeframe.M5, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                FillModel.NEXT_OPEN, 5, null, Splits.NONE, Money.ofRupees(100_000), null);
        List<QualityWarning> warnings = new QualityChecker().check(micro, spec, List.of(), Map.of(), 0, 0, Map.of(), Map.of());
        assertThat(warnings).filteredOn(w -> w.code().equals("MICROSTRUCTURE_NOT_READY")).singleElement().satisfies(w -> {
            assertThat(w.message()).contains("book_imbalance, flow_up_share");
            assertThat(w.severity()).isEqualTo(QualityWarning.Severity.WARN);
        });
        assertThat(new QualityChecker().check(def, spec, List.of(), Map.of(), 0, 0, Map.of(), Map.of()))
                .noneMatch(w -> w.code().equals("MICROSTRUCTURE_NOT_READY"));
    }

    @Test
    void isOosGap() {
        BacktestSpec spec = new BacktestSpec(java.util.UUID.randomUUID(), List.of(), Timeframe.M5, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                FillModel.NEXT_OPEN, 5, null, Splits.DEFAULT_FIXED, Money.ofRupees(100_000), null);
        BacktestMetrics is = new money.hejje.backtest.internal.MetricsCalculator(SyntheticSessions.IST)
                .compute(List.of(MetricsCalculatorTest.trade(LocalDate.of(2026, 9, 1), 10, 1000, 2.0)), Money.ofRupees(100_000), List.of());
        BacktestMetrics oos = new money.hejje.backtest.internal.MetricsCalculator(SyntheticSessions.IST)
                .compute(List.of(MetricsCalculatorTest.trade(LocalDate.of(2026, 9, 20), 10, 100, 0.5)), Money.ofRupees(100_000), List.of());
        List<BacktestTrade> trades = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            trades.add(MetricsCalculatorTest.trade(LocalDate.of(2026, 9, 1), 10 + (i % 3), 100, 1.0));
        }
        List<QualityWarning> warnings = new QualityChecker().check(def, spec, trades, Map.of(Split.IN_SAMPLE, is, Split.OUT_OF_SAMPLE, oos), 22, 22,
                Map.of(), Map.of());
        assertThat(warnings).extracting(QualityWarning::code).contains("IS_OOS_GAP").doesNotContain("INSUFFICIENT_SAMPLE", "LOW_SAMPLE", "MISSING_DATA");
    }
}
