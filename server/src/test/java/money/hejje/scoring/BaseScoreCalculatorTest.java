package money.hejje.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.Split;
import money.hejje.backtest.WalkForwardWindow;
import money.hejje.scoring.internal.BaseScoreCalculator;
import money.hejje.scoring.internal.SlippageSensitivity;
import org.junit.jupiter.api.Test;

class BaseScoreCalculatorTest {

    @Test
    void componentsSumToTheBaseAndWeightsAreDocumented() {
        Backtest b = ScoreFixtures.backtest(UUID.randomUUID(), Map.of(
                Split.IN_SAMPLE, ScoreFixtures.trades(Split.IN_SAMPLE, 120, 2.0, 0.5, LocalDate.of(2024, 1, 1)),
                Split.VALIDATION, ScoreFixtures.trades(Split.VALIDATION, 40, 2.0, 0.5, LocalDate.of(2025, 1, 1)),
                Split.OUT_OF_SAMPLE, ScoreFixtures.trades(Split.OUT_OF_SAMPLE, 60, 1.5, 0.5, LocalDate.of(2025, 6, 1))),
                List.of(new WalkForwardWindow(0, null, null, null, null, 20, 0.3), new WalkForwardWindow(1, null, null, null, null, 20, 0.2),
                        new WalkForwardWindow(2, null, null, null, null, 20, 0.25)));
        BaseScoreCalculator.BaseScore score = BaseScoreCalculator.compute(b, new SlippageSensitivity.Result(0.3, 0.24, 20));
        assertThat(score.components()).extracting(ScoreBreakdown.Component::name).containsExactly("Expectancy (R)", "Profit factor",
                "Max drawdown (R)", "Consistency (profitable months)", "Sample size", "Walk-forward stability", "Slippage sensitivity");
        assertThat(score.components()).extracting(ScoreBreakdown.Component::weight).containsExactly(0.25, 0.20, 0.15, 0.15, 0.10, 0.10, 0.05);
        double sum = score.components().stream().mapToDouble(ScoreBreakdown.Component::contribution).sum();
        assertThat(score.base()).isCloseTo(sum, within(0.35)); // components are rounded to 0.1 each
        assertThat(score.cap()).isNull();
        assertThat(score.base()).isBetween(50.0, 100.0);
        // expectancy component: OOS 0.25R -> 60, validation 0.5R -> 85, IS 0.5R -> 85 ; weighted (60*.6 + 85*.25 + 85*.15) = 70
        ScoreBreakdown.Component expectancy = score.components().get(0);
        assertThat(expectancy.score()).isCloseTo(70.0, within(0.1));
        assertThat(expectancy.evidence()).containsKeys("out_of_sample", "validation", "in_sample");
        // walk-forward std of {0.3, 0.2, 0.25} = 0.05 -> 94
        assertThat(score.components().get(5).score()).isCloseTo(94.0, within(0.1));
        // slippage drop 20% -> 76
        assertThat(score.components().get(6).score()).isCloseTo(76.0, within(0.1));
    }

    @Test
    void fewerThanThirtyOutOfSampleTradesCapsAtFifty() {
        Backtest b = ScoreFixtures.backtest(UUID.randomUUID(), Map.of(
                Split.IN_SAMPLE, ScoreFixtures.trades(Split.IN_SAMPLE, 200, 3.0, 0.6, LocalDate.of(2024, 1, 1)),
                Split.OUT_OF_SAMPLE, ScoreFixtures.trades(Split.OUT_OF_SAMPLE, 10, 3.0, 0.6, LocalDate.of(2025, 6, 1))), List.of());
        BaseScoreCalculator.BaseScore score = BaseScoreCalculator.compute(b, new SlippageSensitivity.Result(1, 1, 0));
        double uncapped = score.components().stream().mapToDouble(ScoreBreakdown.Component::contribution).sum();
        assertThat(uncapped).isGreaterThan(50);
        assertThat(score.base()).isEqualTo(50.0);
        assertThat(score.cap()).contains("only 10 out-of-sample trades");
        // neutral 50s for missing walk-forward windows and missing sensitivity
        BaseScoreCalculator.BaseScore neutral = BaseScoreCalculator.compute(b, null);
        assertThat(neutral.components().get(5).score()).isEqualTo(50.0);
        assertThat(neutral.components().get(6).score()).isEqualTo(50.0);
    }

    @Test
    void losingStrategyScoresNearZeroAndNoBacktestIsZero() {
        Backtest b = ScoreFixtures.backtest(UUID.randomUUID(), Map.of(
                Split.OUT_OF_SAMPLE, ScoreFixtures.trades(Split.OUT_OF_SAMPLE, 60, 0.5, 0.3, LocalDate.of(2025, 6, 1))), List.of());
        BaseScoreCalculator.BaseScore score = BaseScoreCalculator.compute(b, new SlippageSensitivity.Result(-0.5, -0.6, 100));
        assertThat(score.components().get(0).score()).isEqualTo(0.0); // negative expectancy
        assertThat(score.components().get(1).score()).isEqualTo(0.0); // profit factor < 1
        assertThat(score.base()).isLessThan(25);
        assertThat(BaseScoreCalculator.none().base()).isEqualTo(0.0);
        assertThat(BaseScoreCalculator.none().cap()).contains("no completed backtest");
    }

    @Test
    void adjustmentsAreBounded() {
        Adjustment a = new Adjustment("x", 40, -10, 8, List.of());
        assertThat(a.delta()).isEqualTo(8);
        assertThat(new Adjustment("x", -40, -10, 8, List.of()).delta()).isEqualTo(-10);
        assertThat(SlippageSensitivity.class).isNotNull();
    }
}
