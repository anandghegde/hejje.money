package money.hejje.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.BacktestStatus;
import money.hejje.backtest.BacktestTrade;
import money.hejje.backtest.Engine;
import money.hejje.backtest.ExitReason;
import money.hejje.backtest.FillModel;
import money.hejje.backtest.Split;
import money.hejje.backtest.Splits;
import money.hejje.backtest.WalkForwardWindow;
import money.hejje.backtest.internal.MetricsCalculator;
import money.hejje.common.Money;
import money.hejje.common.Side;

/** Builds synthetic DONE backtests with chosen per-split trade lists for scoring tests. */
final class ScoreFixtures {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final UUID INSTRUMENT = UUID.randomUUID();

    private ScoreFixtures() {
    }

    /** {@code n} trades alternating wins of {@code winR} and losses of −1R (net money = R × 1000), one per day from {@code start}. */
    static List<BacktestTrade> trades(Split split, int n, double winR, double winShare, LocalDate start) {
        List<BacktestTrade> out = new ArrayList<>();
        int wins = (int) Math.round(n * winShare);
        for (int i = 0; i < n; i++) {
            boolean win = i < wins;
            double r = win ? winR : -1.0;
            Money net = Money.of(BigDecimal.valueOf(r * 1000).setScale(2, java.math.RoundingMode.HALF_UP));
            LocalDate day = start.plusDays(i);
            out.add(new BacktestTrade(UUID.randomUUID(), null, INSTRUMENT, split, day.atTime(10, 0).atZone(IST).toInstant(),
                    day.atTime(11, 0).atZone(IST).toInstant(), Side.BUY, 10, new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"), null,
                    net.plus(Money.ofRupees(10)), Money.ofRupees(10), net, r, win ? ExitReason.TARGET : ExitReason.STOP, List.of()));
        }
        return out;
    }

    static Backtest backtest(UUID versionId, Map<Split, List<BacktestTrade>> bySplitTrades, List<WalkForwardWindow> windows) {
        MetricsCalculator calc = new MetricsCalculator(IST);
        List<BacktestTrade> all = new ArrayList<>();
        Map<Split, BacktestMetrics> bySplit = new EnumMap<>(Split.class);
        bySplitTrades.forEach((split, trades) -> {
            all.addAll(trades);
            bySplit.put(split, calc.compute(trades, Money.ofRupees(1_000_000), sessions(trades)));
        });
        BacktestMetrics overall = calc.compute(all, Money.ofRupees(1_000_000), sessions(all));
        BacktestSpec spec = new BacktestSpec(versionId, List.of(INSTRUMENT), money.hejje.common.Timeframe.M5, LocalDate.of(2024, 1, 1),
                LocalDate.of(2026, 8, 31), FillModel.NEXT_OPEN, 5, null, windows.isEmpty() ? Splits.DEFAULT_FIXED : Splits.walkForward(6, 2, false),
                Money.ofRupees(1_000_000), null);
        return new Backtest(UUID.randomUUID(), versionId, spec, BacktestStatus.DONE, 100, Instant.now(), Instant.now(), Instant.now(), overall, bySplit,
                windows, List.of(), 600, 600, 0, "hash", Engine.JAVA, null, "test");
    }

    private static List<LocalDate> sessions(List<BacktestTrade> trades) {
        return trades.stream().map(t -> t.exitTime().atZone(IST).toLocalDate()).distinct().sorted().toList();
    }
}
