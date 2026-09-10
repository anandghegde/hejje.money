package money.hejje.backtest.experiments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExperimentRankingTest {

    static SplitSummary split(int trades, double expR, Double pf, double ddR) {
        return new SplitSummary(trades, expR, pf, ddR, 0.5, BigDecimal.ZERO);
    }

    static ExperimentRanking.Candidate c(String name, boolean baseline, SplitSummary is, SplitSummary oos, int trades, double ddR, int params, int conditions,
            double relVol) {
        VariantMetrics m = new VariantMetrics(split(trades, 0.3, 1.4, ddR), is, null, oos, null, 0, List.of(), "h");
        return new ExperimentRanking.Candidate(name, baseline, m, params, conditions, Map.of("entry.all[1]#1", relVol));
    }

    @Test
    void twoCandidatesScoreByTheWeightsTheyWin() {
        // x wins expectancy (0.35) and profit factor (0.15); y wins drawdown (0.15) and simplicity (0.15); trade counts are both
        // capped at 100, a tie worth 0.5 each (0.05); walk-forward is missing for both, so the weights sum to 0.90:
        // x = (0.35 + 0.15 + 0.05) / 0.90 = 61.1, y = (0.15 + 0.15 + 0.05) / 0.90 = 38.9
        ExperimentRanking.Candidate x = new ExperimentRanking.Candidate("x", true, new VariantMetrics(split(120, 0.3, 1.5, 6), null, null, split(40, 0.4, 1.6, 3), null, 0,
                List.of(), "hx"), 6, 3, Map.of());
        ExperimentRanking.Candidate y = new ExperimentRanking.Candidate("y", false, new VariantMetrics(split(110, 0.2, 1.2, 4), null, null, split(35, 0.1, 1.1, 2), null, 0,
                List.of(), "hy"), 5, 2, Map.of());
        ExperimentRanking.Result r = ExperimentRanking.rank(List.of(y, x));
        assertThat(r.variants()).extracting(ExperimentRanking.Ranked::name, ExperimentRanking.Ranked::rank, ExperimentRanking.Ranked::score)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("x", 1, 61.1), org.assertj.core.groups.Tuple.tuple("y", 2, 38.9));
        assertThat(r.variants().get(1).verdict()).isEqualTo("NOT_BETTER");
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void robustBeatsFragileAndOverfittingIsFlagged() {
        List<ExperimentRanking.Candidate> all = new ArrayList<>(List.of(
                c("baseline", true, split(90, 0.25, 1.3, 5), split(60, 0.2, 1.3, 5), 150, 5, 6, 3, 1.2),
                c("vwap_filter", false, split(80, 0.3, 1.5, 4), split(55, 0.35, 1.6, 4), 140, 4, 6, 4, 1.5),
                c("tight_volume", false, split(70, 1.2, 3.0, 2), split(40, 0.25, 1.4, 5), 130, 5, 6, 3, 2.0),
                c("few_trades", false, split(30, 0.5, 1.8, 3), split(20, 0.4, 1.7, 3), 60, 3, 6, 3, 1.3)));
        ExperimentRanking.Result r = ExperimentRanking.rank(all);
        Map<String, ExperimentRanking.Ranked> by = new java.util.HashMap<>();
        r.variants().forEach(v -> by.put(v.name(), v));
        assertThat(by.get("vwap_filter").verdict()).isEqualTo("RECOMMENDED");
        assertThat(by.get("baseline").verdict()).isEqualTo("BASELINE");
        assertThat(by.get("tight_volume").warnings()).anyMatch(w -> w.startsWith("IS_OOS_GAP")).anyMatch(w -> w.startsWith("PARAMETER_AT_EDGE: entry.all[1]#1 = 2"));
        assertThat(by.get("tight_volume").verdict()).isEqualTo("BETTER_BUT_FRAGILE");
        assertThat(by.get("few_trades").warnings()).anyMatch(w -> w.startsWith("LOW_TRADES: 60 trades"));
        assertThat(by.get("baseline").warnings()).anyMatch(w -> w.startsWith("PARAMETER_AT_EDGE")); // 1.2 is the low edge of 1.2..2.0
        assertThat(r.variants()).extracting(ExperimentRanking.Ranked::rank).containsExactly(1, 2, 3, 4);
        assertThat(r.notes()).anyMatch(n -> n.startsWith("MULTIPLE_COMPARISONS: 4 variants"));

        Collections.reverse(all);
        assertThat(ExperimentRanking.rank(all).variants()).isEqualTo(r.variants()); // input order never matters
    }
}
