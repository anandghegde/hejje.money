package money.hejje.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Hand-computed attribution, slippage, adherence and counterfactual over a five-trade fixture (plan M4.5). */
class PerformanceMathTest {

    static TradeFact fact(int n, String family, String trend, long netRupees, BigDecimal exit, Double entryBps, Double exitBps, Integer adherence, Boolean setupValid,
            String exitReason) {
        return new TradeFact(UUID.randomUUID(), UUID.randomUUID(), "NSE:X", UUID.randomUUID(), family.toLowerCase() + "_s", family,
                Instant.parse("2026-09-0" + n + "T04:00:00Z"), Instant.parse("2026-09-0" + n + "T05:00:00Z"), "BUY", 10, new BigDecimal("1000.00"), exit,
                netRupees * 100, 0, netRupees * 100, null, trend + " × NORMAL", trend, n <= 2 ? "HIGH" : "LOW", "NEUTRAL", exitReason, entryBps, exitBps, adherence,
                setupValid, 10);
    }

    // net: -300, -200, -100, +400, +150  → total -50, losses 600, wins 550
    static final List<TradeFact> FACTS = List.of(
            fact(1, "MEAN_REVERSION", "STRONG_UP", -300, new BigDecimal("970.00"), 10.0, 5.0, 50, true, "STOP"),
            fact(2, "MEAN_REVERSION", "STRONG_UP", -200, new BigDecimal("980.00"), 20.0, null, 100, true, "STOP"),
            fact(3, "TREND", "RANGE", -100, new BigDecimal("990.00"), null, -5.0, 100, true, "STOP"),
            fact(4, "TREND", "UP", 400, new BigDecimal("1040.00"), 0.0, 15.0, null, null, "TARGET"),
            fact(5, "MEAN_REVERSION", "RANGE", 150, new BigDecimal("1015.00"), null, null, 0, false, "MANUAL"));

    static BigDecimal r(String v) {
        return new BigDecimal(v);
    }

    @Test
    void lossAttributionSharesAndHeadline() {
        PerformanceMath.LossAttribution a = PerformanceMath.attribute(FACTS);
        assertThat(a.trades()).isEqualTo(5);
        assertThat(a.winners()).isEqualTo(2);
        assertThat(a.losers()).isEqualTo(3);
        assertThat(a.netPnl()).isEqualByComparingTo("-50");
        assertThat(a.grossLosses()).isEqualByComparingTo("600");
        assertThat(a.grossWins()).isEqualByComparingTo("550");
        List<PerformanceMath.Bucket> family = a.dimensions().stream().filter(d -> d.name().equals("family")).findFirst().orElseThrow().buckets();
        assertThat(family).extracting(PerformanceMath.Bucket::key, PerformanceMath.Bucket::trades, PerformanceMath.Bucket::losers)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("MEAN_REVERSION", 3, 2), org.assertj.core.groups.Tuple.tuple("TREND", 2, 1));
        assertThat(family.get(0).losses()).isEqualByComparingTo("500");
        assertThat(family.get(0).lossSharePct()).isEqualByComparingTo("83.3");
        assertThat(family.get(0).netPnl()).isEqualByComparingTo("-350");
        assertThat(family.get(1).lossSharePct()).isEqualByComparingTo("16.7");
        List<PerformanceMath.Bucket> event = a.dimensions().stream().filter(d -> d.name().equals("event")).findFirst().orElseThrow().buckets();
        assertThat(event.get(0)).extracting(PerformanceMath.Bucket::key, PerformanceMath.Bucket::lossSharePct).containsExactly("HIGH", r("83.3"));
        assertThat(a.familyByTrend().get(0).key()).isEqualTo("MEAN_REVERSION × STRONG_UP");
        assertThat(a.headline()).isEqualTo("83.3% of losses came from mean reversion strategies during strong up sessions (2 of 2 trades lost).");
    }

    @Test
    void lossAttributionBreaksLossesDownByCause() {
        List<TradeFact> facts = new java.util.ArrayList<>();
        String[] causes = {"NOISE_STOP", "NOISE_STOP", "THESIS_BREAK", "CLEAN_TARGET", "DRIFT"};
        for (int i = 0; i < FACTS.size(); i++) {
            TradeFact f = FACTS.get(i);
            facts.add(new TradeFact(f.entryOrderId(), f.instrumentId(), f.instrument(), f.strategyId(), f.strategy(), f.family(), f.openedAt(), f.closedAt(),
                    f.side(), f.quantity(), f.entryPrice(), f.exitPrice(), f.grossPaise(), f.feesPaise(), f.netPaise(), f.outcomeR(), f.regime(), f.trend(),
                    f.event(), f.news(), f.exitReason(), f.entrySlippageBps(), f.exitSlippageBps(), f.adherencePct(), f.setupValid(), f.hour(), causes[i],
                    i == 0 ? "EARLY" : "GOOD"));
        }
        PerformanceMath.LossAttribution a = PerformanceMath.attribute(facts);
        List<PerformanceMath.Bucket> cause = a.dimensions().stream().filter(d -> d.name().equals("cause")).findFirst().orElseThrow().buckets();
        assertThat(cause.get(0)).extracting(PerformanceMath.Bucket::key, PerformanceMath.Bucket::losers, PerformanceMath.Bucket::lossSharePct)
                .containsExactly("NOISE_STOP", 2, r("83.3"));
        assertThat(a.dimensions()).extracting(PerformanceMath.Dimension::name).contains("cause", "entryTiming");
        // facts built before M9.6 read UNKNOWN
        assertThat(PerformanceMath.attribute(FACTS).dimensions().stream().filter(d -> d.name().equals("cause")).findFirst().orElseThrow().buckets())
                .extracting(PerformanceMath.Bucket::key).containsExactly("UNKNOWN");
    }

    @Test
    void slippageStatsAndCost() {
        PerformanceMath.SlippageStats s = PerformanceMath.slippage(FACTS);
        // entry bps [10, 20, 0] on 1000 × 10: cost 10 + 20 + 0 = 30
        assertThat(s.entry()).extracting(PerformanceMath.SlippageSide::trades, PerformanceMath.SlippageSide::meanBps, PerformanceMath.SlippageSide::medianBps,
                PerformanceMath.SlippageSide::p90Bps, PerformanceMath.SlippageSide::worstBps).containsExactly(3, 10.0, 10.0, 20.0, 20.0);
        assertThat(s.entry().costRupees()).isEqualByComparingTo("30.00");
        // exit bps [5 on 970, -5 on 990, 15 on 1040] × 10: 4.85 - 4.95 + 15.60 = 15.50
        assertThat(s.exit()).extracting(PerformanceMath.SlippageSide::meanBps, PerformanceMath.SlippageSide::medianBps, PerformanceMath.SlippageSide::worstBps)
                .containsExactly(5.0, 5.0, 15.0);
        assertThat(s.exit().costRupees()).isEqualByComparingTo("15.50");
        assertThat(s.totalCostRupees()).isEqualByComparingTo("45.50");
        assertThat(PerformanceMath.slippage(List.of()).entry().meanBps()).isNull();
    }

    @Test
    void ruleAdherence() {
        PerformanceMath.AdherenceStats a = PerformanceMath.adherence(FACTS);
        assertThat(a.trades()).isEqualTo(5);
        assertThat(a.withAdherence()).isEqualTo(4);
        assertThat(a.meanAdherencePct()).isEqualTo(62.5);
        assertThat(a.fullAdherence()).isEqualTo(2);
        assertThat(a.setupInvalid()).isEqualTo(1);
        assertThat(a.manualExits()).isEqualTo(1);
        assertThat(a.netFullAdherence()).isEqualByComparingTo("-300");
        assertThat(a.netPartialAdherence()).isEqualByComparingTo("-150");
    }

    @Test
    void counterfactualRemovesTheCombinationAndKeepsActualAlongside() {
        PerformanceMath.CounterfactualFilter filter = new PerformanceMath.CounterfactualFilter(null, List.of("STRONG_UP"), List.of("MEAN_REVERSION"), null, null, null,
                null, null);
        PerformanceMath.Counterfactual c = PerformanceMath.counterfactual(FACTS, filter);
        assertThat(c.basis()).isEqualTo("SIMULATED");
        assertThat(c.note()).contains("Hypothetical");
        // actual equity -300, -500, -600, -200, -50 → drawdown 600; simulated -100, 300, 450 → drawdown 100
        assertThat(c.actual()).extracting(PerformanceMath.Outcome::trades, PerformanceMath.Outcome::winners).containsExactly(5, 2);
        assertThat(c.actual().netPnl()).isEqualByComparingTo("-50");
        assertThat(c.actual().maxDrawdown()).isEqualByComparingTo("600");
        assertThat(c.actual().profitFactor()).isEqualTo(0.9167);
        assertThat(c.simulated().trades()).isEqualTo(3);
        assertThat(c.simulated().netPnl()).isEqualByComparingTo("450");
        assertThat(c.simulated().maxDrawdown()).isEqualByComparingTo("100");
        assertThat(c.simulated().profitFactor()).isEqualTo(5.5);
        assertThat(c.simulated().winRate()).isEqualTo(0.6667);
        assertThat(c.excludedTrades()).isEqualTo(2);
        assertThat(c.excludedNetPnl()).isEqualByComparingTo("-500");
        assertThat(c.netDifference()).isEqualByComparingTo("500");
        assertThat(c.drawdownDifference()).isEqualByComparingTo("-500");
        // OR within a category: both families on strong-up days is still just the two MR trades
        assertThat(PerformanceMath.counterfactual(FACTS, new PerformanceMath.CounterfactualFilter(null, List.of("strong_up"), List.of("TREND", "MEAN_REVERSION"), null,
                null, null, null, null)).excludedTrades()).isEqualTo(2);
        assertThat(PerformanceMath.counterfactual(FACTS, new PerformanceMath.CounterfactualFilter(null, null, null, null, List.of("HIGH"), null, null, null))
                .simulated().netPnl()).isEqualByComparingTo("450");
    }
}
