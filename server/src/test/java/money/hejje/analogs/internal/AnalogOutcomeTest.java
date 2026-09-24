package money.hejje.analogs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import money.hejje.analogs.AnalogSummary;
import org.junit.jupiter.api.Test;

class AnalogOutcomeTest {

    /** A match whose cumulative path rises or falls linearly to {@code finalPct} over five steps, with an optional dip first. */
    static OutcomeStats.Followed followed(String symbol, int year, double finalPct, double dip) {
        double[] path = new double[5];
        for (int k = 0; k < 5; k++) {
            path[k] = finalPct * (k + 1) / 5.0;
        }
        if (dip < 0) {
            path[0] = dip;
        }
        return new OutcomeStats.Followed(symbol, LocalDate.of(year, 3, 10), path);
    }

    static List<OutcomeStats.Followed> matches(double... finals) {
        List<OutcomeStats.Followed> out = new ArrayList<>();
        for (int i = 0; i < finals.length; i++) {
            out.add(followed("S" + (i % 20), 2018 + i % 6, finals[i], 0));
        }
        return out;
    }

    @Test
    void distributionStatisticsAreHandCheckable() {
        // returns 1..10 %: mean 5.5, median 5.5, p25 3.25, p75 7.75 (linear interpolation), all positive
        AnalogSummary.Outcome o = OutcomeStats.outcome("5", 5, Math.sqrt(5), matches(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), AnalogFixtures.PROPS);
        assertThat(o.count()).isEqualTo(10);
        assertThat(o.winRate()).isEqualTo(1.0);
        assertThat(o.mean()).isEqualTo(5.5);
        assertThat(o.median()).isEqualTo(5.5);
        assertThat(o.p25()).isEqualTo(3.25);
        assertThat(o.p75()).isEqualTo(7.75);
        assertThat(o.best()).isEqualTo(10.0);
        assertThat(o.worst()).isEqualTo(1.0);
        assertThat(o.maeMedian()).isZero();           // a rising path never goes below its start
        assertThat(o.mfeMedian()).isEqualTo(5.5);
        assertThat(o.avgPath()).containsExactly(1.1, 2.2, 3.3, 4.4, 5.5);
        assertThat(o.distinctSymbols()).isEqualTo(10);
        assertThat(o.distinctYears()).isEqualTo(6);
        assertThat(o.direction()).isEqualTo("BULLISH_STRONG");
        assertThat(o.risk()).isEqualTo("LOW");
        assertThat(o.reliability()).isEqualTo("LOW"); // ten matches: enough for a direction, not for MEDIUM (15)
    }

    @Test
    void belowMinEvidenceEveryTagSaysSo() {
        AnalogSummary.Outcome o = OutcomeStats.outcome("5", 5, Math.sqrt(5), matches(1, 2, 3, 4, 5, 6, 7, 8, 9), AnalogFixtures.PROPS);
        assertThat(o.count()).isEqualTo(9);
        assertThat(o.direction()).isEqualTo("INSUFFICIENT");
        assertThat(o.reliability()).isEqualTo("INSUFFICIENT");
        assertThat(o.consistency()).isEqualTo("NONE");
        assertThat(o.risk()).isEqualTo("NONE");
    }

    @Test
    void directionNeedsWinRateAndMedianOnTheSameSide() {
        assertThat(OutcomeStats.direction(20, 0.70, 1.2, AnalogFixtures.PROPS)).isEqualTo("BULLISH_STRONG");
        assertThat(OutcomeStats.direction(20, 0.60, 1.2, AnalogFixtures.PROPS)).isEqualTo("BULLISH");
        assertThat(OutcomeStats.direction(20, 0.60, -0.1, AnalogFixtures.PROPS)).isEqualTo("MIXED"); // more winners, but the typical outcome is a loss
        assertThat(OutcomeStats.direction(20, 0.50, 0.4, AnalogFixtures.PROPS)).isEqualTo("MIXED");
        assertThat(OutcomeStats.direction(20, 0.40, -0.8, AnalogFixtures.PROPS)).isEqualTo("BEARISH");
        assertThat(OutcomeStats.direction(20, 0.30, -0.8, AnalogFixtures.PROPS)).isEqualTo("BEARISH_STRONG");
    }

    @Test
    void reliabilityCountsMatchesSymbolsAndYears() {
        assertThat(OutcomeStats.reliability(40, 20, 5, AnalogFixtures.PROPS)).isEqualTo("HIGH");
        assertThat(OutcomeStats.reliability(40, 3, 5, AnalogFixtures.PROPS)).isEqualTo("LOW");     // forty matches from three symbols
        assertThat(OutcomeStats.reliability(40, 20, 1, AnalogFixtures.PROPS)).isEqualTo("LOW");    // ... or from a single year
        assertThat(OutcomeStats.reliability(20, 10, 3, AnalogFixtures.PROPS)).isEqualTo("MEDIUM");
    }

    @Test
    void consistencyAndRiskScaleWithTheSquareRootOfTheWindow() {
        double scale = Math.sqrt(5); // TIGHT up to 1.79 %, WIDE from 3.58 %; HIGH risk from a median MAE of -2.24 %
        assertThat(OutcomeStats.consistency(20, 1.5, scale, AnalogFixtures.PROPS)).isEqualTo("TIGHT");
        assertThat(OutcomeStats.consistency(20, 2.5, scale, AnalogFixtures.PROPS)).isEqualTo("NORMAL");
        assertThat(OutcomeStats.consistency(20, 4.0, scale, AnalogFixtures.PROPS)).isEqualTo("WIDE");
        List<OutcomeStats.Followed> dippers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            dippers.add(followed("S" + i, 2020, 2.0, -3.0));
        }
        AnalogSummary.Outcome o = OutcomeStats.outcome("5", 5, scale, dippers, AnalogFixtures.PROPS);
        assertThat(o.maeMedian()).isEqualTo(-3.0);
        assertThat(o.risk()).isEqualTo("HIGH");
    }

    @Test
    void twoExtremeMatchesThatCarryTheMeanAreFlagged() {
        assertThat(OutcomeStats.outcome("5", 5, 1, matches(0.1, 0.2, -0.1, 0.0, 0.1, -0.2, 0.1, 0.0, 25, 30), AnalogFixtures.PROPS).outlier()).isTrue();
        assertThat(OutcomeStats.outcome("5", 5, 1, matches(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), AnalogFixtures.PROPS).outlier()).isFalse();
    }

    @Test
    void splitsCarryBothCounts() {
        List<OutcomeStats.Followed> all = new ArrayList<>(matches(1, 2, 3, 4));
        all.add(new OutcomeStats.Followed("X", LocalDate.of(2021, 9, 1), new double[] {0, 0, 0, 0, -2}));
        AnalogSummary.Split split = OutcomeStats.split("sameMonth", "5", 5, all, f -> f.date().getMonthValue() == 9);
        assertThat(split.count()).isEqualTo(1);
        assertThat(split.median()).isEqualTo(-2.0);
        assertThat(split.winRate()).isZero();
        assertThat(split.otherCount()).isEqualTo(4);
        assertThat(split.otherWinRate()).isEqualTo(1.0);
        assertThat(split.otherMedian()).isEqualTo(2.5);
    }

    @Test
    void noMatchesGiveAnEmptyOutcome() {
        AnalogSummary.Outcome o = OutcomeStats.outcome("5", 5, 1, List.of(), AnalogFixtures.PROPS);
        assertThat(o.count()).isZero();
        assertThat(o.direction()).isEqualTo("INSUFFICIENT");
    }
}
