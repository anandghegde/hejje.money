package money.hejje.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.backtest.internal.MetricsCalculator;
import money.hejje.common.Money;
import money.hejje.common.Side;
import org.junit.jupiter.api.Test;

class MetricsCalculatorTest {

    static final UUID I = UUID.randomUUID();

    static BacktestTrade trade(LocalDate day, int hour, long netRupees, double r) {
        Money net = Money.ofRupees(netRupees);
        Money costs = Money.ofRupees(10);
        return new BacktestTrade(UUID.randomUUID(), null, I, Split.IN_SAMPLE, day.atTime(LocalTime.of(hour, 0)).atZone(SyntheticSessions.IST).toInstant(),
                day.atTime(LocalTime.of(hour, 30)).atZone(SyntheticSessions.IST).toInstant(), Side.BUY, 10, new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("99"), null, net.plus(costs), costs, net, r, ExitReason.TARGET, List.of());
    }

    @Test
    void handComputedMetrics() {
        LocalDate d1 = LocalDate.of(2026, 9, 1);
        LocalDate d2 = LocalDate.of(2026, 9, 2);
        LocalDate d3 = LocalDate.of(2026, 9, 3);
        List<BacktestTrade> trades = List.of(
                trade(d1, 10, 1000, 2.0),
                trade(d1, 12, -500, -1.0),
                trade(d2, 10, 2000, 4.0),
                trade(d2, 12, -500, -1.0),
                trade(d3, 10, 500, 1.0));
        BacktestMetrics m = new MetricsCalculator(SyntheticSessions.IST).compute(trades, Money.ofRupees(100_000), List.of(d1, d2, d3));
        assertThat(m.totalTrades()).isEqualTo(5);
        assertThat(m.winningTrades()).isEqualTo(3);
        assertThat(m.losingTrades()).isEqualTo(2);
        assertThat(m.winRate()).isCloseTo(0.6, within(1e-12));
        assertThat(m.lossRate()).isCloseTo(0.4, within(1e-12));
        assertThat(m.averageWin()).isEqualTo(Money.of("1166.67"));
        assertThat(m.averageLoss()).isEqualTo(Money.of("-500.00"));
        assertThat(m.winLossRatio()).isCloseTo(1166.67 / 500, within(1e-4));
        assertThat(m.expectancyR()).isCloseTo(1.0, within(1e-12));
        assertThat(m.expectancyMoney()).isEqualTo(Money.of("500.00"));
        assertThat(m.profitFactor()).isCloseTo(3.5, within(1e-12));
        assertThat(m.netPnl()).isEqualTo(Money.of("2500.00"));
        assertThat(m.totalCosts()).isEqualTo(Money.of("50.00"));
        assertThat(m.grossPnl()).isEqualTo(Money.of("2550.00"));
        assertThat(m.totalReturnPct()).isCloseTo(2.5, within(1e-12));
        assertThat(m.cagrPct()).isNull(); // under one year
        // equity: 1000, 500, 2500, 2000, 2500 -> max drawdown 500 (both dips)
        assertThat(m.maxDrawdown()).isEqualTo(Money.of("500.00"));
        assertThat(m.maxDrawdownR()).isCloseTo(1.0, within(1e-12));
        assertThat(m.maxDrawdownPct()).isCloseTo(0.5, within(1e-12));
        assertThat(m.maxDrawdownDurationDays()).isEqualTo(1); // peak on d2 (2500), below until d3
        assertThat(m.maxConsecutiveWins()).isEqualTo(1);
        assertThat(m.maxConsecutiveLosses()).isEqualTo(1);
        assertThat(m.averageHoldingMinutes()).isCloseTo(30.0, within(1e-12));
        assertThat(m.largestWin()).isEqualTo(Money.of("2000.00"));
        assertThat(m.largestLoss()).isEqualTo(Money.of("-500.00"));
        // buckets are lower-inclusive: exactly -1R lands in "-1R..0", exactly 1R in "1R..2R"
        assertThat(m.rDistribution()).containsEntry("-1R..0", 2).containsEntry("-2R..-1R", 0).containsEntry("1R..2R", 1).containsEntry("2R..3R", 1)
                .containsEntry("> 3R", 1);
        // daily returns: 0.5%, 1.5%, 0.5% -> mean 0.8333%, sample sd 0.5774% -> sharpe = 1.4434 * sqrt(252)
        assertThat(m.sharpe()).isCloseTo(0.008333333 / 0.005773503 * Math.sqrt(252), within(1e-3));
        assertThat(m.sortino()).isNull(); // no negative day
        assertThat(m.monthly()).containsKey("2026-09");
        assertThat(m.monthly().get("2026-09").trades()).isEqualTo(5);
        assertThat(m.dayOfWeek()).containsKeys("TUE", "WED", "THU");
        assertThat(m.hourOfDay().get("10").trades()).isEqualTo(3);
        assertThat(m.equityCurve()).hasSize(5);
        assertThat(m.equityCurve().get(4).value()).isEqualTo(Money.ofRupees(102_500));
        assertThat(m.drawdownCurve().get(1).value()).isEqualTo(Money.of("-500.00"));
    }

    @Test
    void emptyAndAllWinning() {
        BacktestMetrics empty = new MetricsCalculator(SyntheticSessions.IST).compute(List.of(), Money.ofRupees(100_000), List.of());
        assertThat(empty.totalTrades()).isZero();
        assertThat(empty.profitFactor()).isNull();
        assertThat(empty.sharpe()).isNull();
        assertThat(empty.maxDrawdown()).isEqualTo(Money.ZERO);

        List<BacktestTrade> wins = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            wins.add(trade(LocalDate.of(2026, 9, 1 + i), 10, 100, 1.0));
        }
        BacktestMetrics m = new MetricsCalculator(SyntheticSessions.IST).compute(wins, Money.ofRupees(100_000),
                List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3)));
        assertThat(m.profitFactor()).isNull();
        assertThat(m.winLossRatio()).isNull();
        assertThat(m.maxConsecutiveWins()).isEqualTo(3);
        assertThat(m.sharpe()).isNull(); // identical daily returns: zero deviation
    }
}
