package money.hejje.analytics.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic trade streams crossing each drift threshold (plan M5.1) plus the statistics behind them. */
class DriftMathTest {

    static final DriftProperties P = new DriftProperties(true, Duration.ofMinutes(10), 30, 60, 10, 2000, 0.90, new BigDecimal("0.50"), null, null, null);

    /** The backtest: 60 % winners at +1R / −0.5R style edge, expectancy +0.3R, max drawdown 4R. */
    static final DriftMath.Baseline BASE = new DriftMath.Baseline(200, 0.60, 0.30, 1.8, 4.0);

    static List<Double> stream(int wins, double winR, int losses, double lossR) {
        List<Double> rs = new ArrayList<>();
        for (int i = 0; i < wins; i++) {
            rs.add(winR);
        }
        for (int i = 0; i < losses; i++) {
            rs.add(lossR);
        }
        Collections.shuffle(rs, new java.util.Random(7));
        return rs;
    }

    static int wins(List<Double> rs) {
        return (int) rs.stream().filter(r -> r > 0).count();
    }

    @Test
    void binomialCdfMatchesKnownValues() {
        assertThat(DriftMath.binomialCdf(2, 10, 0.5)).isCloseTo(56.0 / 1024, within(1e-12)); // (1 + 10 + 45) / 2^10
        assertThat(DriftMath.binomialCdf(10, 10, 0.3)).isEqualTo(1.0);
        assertThat(DriftMath.binomialCdf(0, 5, 0.2)).isCloseTo(Math.pow(0.8, 5), within(1e-12));
        assertThat(DriftMath.binomialCdf(14, 30, 0.61)).isCloseTo(0.078841, within(1e-6)); // PRD 25: 47 % of 30 vs 61 % (exact rational sum)
    }

    @Test
    void bootstrapIsDeterministicAndBracketsTheMean() {
        List<Double> rs = stream(12, 1.0, 8, -1.0);
        double[] a = DriftMath.bootstrapMeanCi(rs, 2000, 0.90);
        double[] b = DriftMath.bootstrapMeanCi(rs, 2000, 0.90);
        assertThat(a).containsExactly(b);
        assertThat(a[0]).isLessThan(0.2).isGreaterThan(-0.3);
        assertThat(a[1]).isGreaterThan(0.2).isLessThan(0.7);
    }

    @Test
    void drawdownAndProfitFactorOnR() {
        assertThat(DriftMath.maxDrawdownR(List.of(1.0, -1.0, -1.0, 2.0, -3.0, 1.0))).isEqualTo(3.0);
        assertThat(DriftMath.profitFactor(List.of(2.0, -1.0, 1.0, -1.0))).isEqualTo(1.5);
        assertThat(DriftMath.profitFactor(List.of(1.0, 2.0))).isNull();
    }

    @Test
    void tooFewTradesOrNoBacktestIsInsufficientData() {
        List<Double> nine = stream(3, 1.0, 6, -1.0);
        assertThat(DriftMath.assess(nine, wins(nine), BASE, P).status()).isEqualTo(DriftStatus.INSUFFICIENT_DATA);
        List<Double> thirty = stream(18, 1.0, 12, -0.5);
        DriftMath.Assessment none = DriftMath.assess(thirty, wins(thirty), null, P);
        assertThat(none.status()).isEqualTo(DriftStatus.INSUFFICIENT_DATA);
        assertThat(none.evidence()).anyMatch(e -> e.contains("no completed backtest"));
    }

    @Test
    void inLineWithTheBacktestIsHealthy() {
        List<Double> rs = stream(18, 1.0, 12, -0.8); // 60 % wins, +0.28R
        DriftMath.Assessment a = DriftMath.assess(rs, wins(rs), BASE, P);
        assertThat(a.status()).isEqualTo(DriftStatus.HEALTHY);
        assertThat(a.triggered()).isEmpty();
        assertThat(a.evidence()).anyMatch(e -> e.startsWith("win rate 60% over 30 trades vs 60% backtest"));
    }

    @Test
    void halfTheExpectancyIsWatch() {
        List<Double> rs = stream(18, 0.7, 12, -0.8); // 60 % wins but +0.10R: a third of the backtest's
        DriftMath.Assessment a = DriftMath.assess(rs, wins(rs), BASE, P);
        assertThat(a.status()).isEqualTo(DriftStatus.WATCH);
        assertThat(a.triggered()).singleElement().asString().contains("33% of the backtest's +0.30R");
    }

    @Test
    void aWinRateUnlikelyByChanceIsDegrading() {
        List<Double> rs = stream(11, 1.8, 19, -0.5); // 37 % wins (p ≈ 0.0083) but still +0.34R: only the win-rate test fires
        DriftMath.Assessment a = DriftMath.assess(rs, wins(rs), BASE, P);
        assertThat(a.status()).isEqualTo(DriftStatus.DEGRADING);
        assertThat(a.stats().winRatePValue()).isLessThan(0.05);
        assertThat(a.triggered()).singleElement().asString().contains("unlikely by chance");
    }

    @Test
    void losingWithConfidenceIsFailed() {
        List<Double> rs = stream(9, 1.0, 21, -1.0); // 30 % wins, −0.40R
        DriftMath.Assessment a = DriftMath.assess(rs, wins(rs), BASE, P);
        assertThat(a.status()).isEqualTo(DriftStatus.FAILED);
        assertThat(a.stats().expectancyHigh()).isLessThan(0);
        assertThat(a.triggered()).anyMatch(t -> t.contains("losing with confidence"));
    }

    @Test
    void drawdownMultiplesEscalate() {
        DriftMath.Baseline shallow = new DriftMath.Baseline(200, 0.60, 0.30, 1.8, 2.0);
        // +0.30R mean and 60 % wins, but seven losses in a row: a 7R drawdown against a 2R backtest drawdown (3.5×)
        List<Double> rs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            rs.add(1.0);
        }
        for (int i = 0; i < 7; i++) {
            rs.add(-1.0);
        }
        for (int i = 0; i < 2; i++) {
            rs.add(1.0);
        }
        for (int i = 0; i < 5; i++) {
            rs.add(-0.6);
        }
        DriftMath.Assessment a = DriftMath.assess(rs, wins(rs), shallow, P);
        assertThat(a.stats().drawdownMultiple()).isGreaterThanOrEqualTo(2.0);
        assertThat(a.status()).isEqualTo(DriftStatus.FAILED);
        assertThat(a.triggered()).anyMatch(t -> t.contains("× the backtest's 2.0R"));
    }

    @Test
    void propertiesDefaultAndClampPoints() {
        assertThat(P.actionsFor(DriftStatus.FAILED)).contains(DriftAction.PAUSE);
        assertThat(P.actionsFor(DriftStatus.HEALTHY)).isEmpty();
        assertThat(P.pointsFor(DriftStatus.FAILED)).isEqualTo(-15);
        DriftProperties loud = new DriftProperties(true, Duration.ofMinutes(10), 30, 60, 10, 2000, 0.9, BigDecimal.ONE, null, null,
                java.util.Map.of(DriftStatus.WATCH, -40, DriftStatus.DEGRADING, 5));
        assertThat(loud.pointsFor(DriftStatus.WATCH)).isEqualTo(-15);
        assertThat(loud.pointsFor(DriftStatus.DEGRADING)).isZero();
    }
}
