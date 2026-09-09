package money.hejje.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.backtest.SyntheticSessions.Ohlc;
import money.hejje.common.InstrumentType;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.costs.CostFill;
import money.hejje.common.costs.CostModel;
import money.hejje.common.costs.CostProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.internal.DefinitionParser;
import org.junit.jupiter.api.Test;

class BacktestEngineTest {

    static final ZoneId IST = SyntheticSessions.IST;
    static final UUID INSTRUMENT = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    static final LocalDate WARMUP = LocalDate.of(2026, 9, 7);
    static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    static final InstrumentMeta META = new InstrumentMeta(INSTRUMENT, "NSE:TEST", InstrumentType.EQ, 1, new BigDecimal("0.05"));

    static final String ORB = """
            name: orb_test
            universe: [NSE:TEST]
            timeframe: 5m
            direction: long
            entry:
              all:
                - close > opening_range_high
            stop:
              type: opening_range_low
            target:
              type: risk_multiple
              value: 2
            trade_window:
              start: "09:30"
              end: "12:00"
            max_trades_per_day: 1
            """;

    static CostProperties costProperties() {
        return new CostProperties(true, new BigDecimal("20"), new BigDecimal("0.0003"), new BigDecimal("0.18"), new BigDecimal("0.000001"),
                new CostProperties.Segments(new BigDecimal("0.00025"), true, new BigDecimal("0.0000297"), new BigDecimal("0.00003"), false),
                new CostProperties.Segments(new BigDecimal("0.001"), false, new BigDecimal("0.0000297"), new BigDecimal("0.00015"), true),
                new CostProperties.Segments(new BigDecimal("0.0002"), true, new BigDecimal("0.0000173"), new BigDecimal("0.00002"), false),
                new CostProperties.Segments(new BigDecimal("0.001"), true, new BigDecimal("0.0003503"), new BigDecimal("0.00003"), false));
    }

    final CostModel costs = new CostModel(costProperties());
    final HejjeClock clock = new HejjeClock(Clock.fixed(LocalDateTime.of(2026, 9, 9, 10, 0).atZone(IST).toInstant(), IST), IST, (d, e) -> false);
    final BacktestEngine engine = new BacktestEngine(costs, clock);
    final StrategyDefinition orb = new DefinitionParser().parse(ORB);

    static BacktestSpec spec(UUID versionId, LocalDate from, LocalDate to, FillModel fill, int slippage) {
        return new BacktestSpec(versionId, List.of(INSTRUMENT), Timeframe.M5, from, to, fill, slippage, null, Splits.NONE, Money.ofRupees(1_000_000), null);
    }

    /** Opening range 100..102, breakout close 103 on the 09:30 bar, then the given bars. */
    static List<Candle> breakoutDay(List<Ohlc> afterBreakout) {
        List<Ohlc> bars = new ArrayList<>(List.of(
                SyntheticSessions.bar("101", "102", "100", "101"),
                SyntheticSessions.bar("101", "101.5", "100.5", "101"),
                SyntheticSessions.bar("101", "101.8", "100.2", "101.5"),
                SyntheticSessions.bar("101.5", "103.2", "101.4", "103")));
        bars.addAll(afterBreakout);
        return SyntheticSessions.session(INSTRUMENT, DAY, bars, 50_000);
    }

    BacktestResult run(List<Candle> day, FillModel fill, int slippage) {
        List<Candle> candles = new ArrayList<>(SyntheticSessions.flat(INSTRUMENT, WARMUP, "101"));
        candles.addAll(day);
        BacktestInput input = new BacktestInput(orb, spec(UUID.randomUUID(), DAY, DAY, fill, slippage), Map.of(INSTRUMENT, META),
                Map.of(INSTRUMENT, candles), Money.ofRupees(2000));
        return engine.run(input);
    }

    @Test
    void breakoutDayProducesExactlyOneTradeToTarget() {
        // entry at the 09:35 open (103.50), stop = OR low 100 -> risk 3.50/unit, target = 103.50 + 7 = 110.50, hit on the 09:40 bar
        BacktestResult result = run(breakoutDay(List.of(
                SyntheticSessions.bar("103.5", "104", "103", "103.8"),
                SyntheticSessions.bar("103.8", "111", "103.5", "110"))), FillModel.NEXT_OPEN, 0);
        assertThat(result.trades()).hasSize(1);
        BacktestTrade t = result.trades().get(0);
        assertThat(t.side()).isEqualTo(Side.BUY);
        assertThat(t.entryTime()).isEqualTo(DAY.atTime(9, 35).atZone(IST).toInstant());
        assertThat(t.entryPrice()).isEqualByComparingTo("103.50");
        assertThat(t.stop()).isEqualByComparingTo("100.00");
        assertThat(t.target()).isEqualByComparingTo("110.50");
        assertThat(t.exitPrice()).isEqualByComparingTo("110.50");
        assertThat(t.exitTime()).isEqualTo(DAY.atTime(9, 40).atZone(IST).toInstant());
        assertThat(t.exitReason()).isEqualTo(ExitReason.TARGET);
        assertThat(t.qty()).isEqualTo(571); // floor(2000 / 3.50)
        assertThat(t.grossPnl()).isEqualTo(Money.of("3997.00"));
        Money expectedCosts = costs.compute(new CostFill(InstrumentType.EQ, Product.MIS, Side.BUY, 571, new BigDecimal("103.50"))).total()
                .plus(costs.compute(new CostFill(InstrumentType.EQ, Product.MIS, Side.SELL, 571, new BigDecimal("110.50"))).total());
        assertThat(t.costs()).isEqualTo(expectedCosts);
        assertThat(t.netPnl()).isEqualTo(t.grossPnl().minus(expectedCosts));
        assertThat(t.rMultiple()).isCloseTo(t.netPnl().toRupees().doubleValue() / (3.5 * 571), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(t.evidence()).hasSize(1);
        assertThat(t.evidence().get(0)).containsEntry("condition", "close > opening_range_high(15m)").containsEntry("status", "PASSED")
                .containsEntry("lhs", 103.0).containsEntry("rhs", 102.0);
        assertThat(result.overall().totalTrades()).isEqualTo(1);
        assertThat(result.overall().winningTrades()).isEqualTo(1);
        assertThat(result.overall().netPnl()).isEqualTo(t.netPnl());
        assertThat(result.overall().totalCosts()).isEqualTo(expectedCosts);
        assertThat(result.overall().grossPnl()).isEqualTo(Money.of("3997.00"));
        assertThat(result.sessionsWithData()).isEqualTo(1);
        // warm-up session produced no trades (out of range)
        assertThat(result.trades()).allMatch(x -> x.entryTime().atZone(IST).toLocalDate().equals(DAY));
    }

    @Test
    void bothStopAndTargetTouchedResolvesToStop() {
        BacktestResult result = run(breakoutDay(List.of(
                SyntheticSessions.bar("103.5", "104", "103", "103.8"),
                SyntheticSessions.bar("103.8", "112", "99", "105"))), FillModel.NEXT_OPEN, 0);
        assertThat(result.trades()).hasSize(1);
        BacktestTrade t = result.trades().get(0);
        assertThat(t.exitReason()).isEqualTo(ExitReason.STOP);
        assertThat(t.exitPrice()).isEqualByComparingTo("100.00");
        assertThat(t.grossPnl()).isEqualTo(Money.of("-1998.50")); // -3.50 * 571
        assertThat(t.rMultiple()).isLessThan(-1.0); // costs push it past -1R
        assertThat(t.isWin()).isFalse();
    }

    @Test
    void gapThroughStopFillsAtOpen() {
        BacktestResult result = run(breakoutDay(List.of(
                SyntheticSessions.bar("103.5", "104", "103", "103.8"),
                SyntheticSessions.bar("98", "99", "97", "98"))), FillModel.NEXT_OPEN, 0);
        assertThat(result.trades().get(0).exitPrice()).isEqualByComparingTo("98.00");
        assertThat(result.trades().get(0).exitReason()).isEqualTo(ExitReason.STOP);
    }

    @Test
    void forceExitAt1510WhenNothingElseTriggers() {
        // after the entry the price drifts at 105 for the whole day
        List<Ohlc> drift = new ArrayList<>();
        drift.add(SyntheticSessions.bar("103.5", "104", "103", "103.8"));
        for (int i = 0; i < 70; i++) {
            drift.add(SyntheticSessions.bar("105", "105.5", "104.5", "105"));
        }
        BacktestResult result = run(breakoutDay(drift), FillModel.NEXT_OPEN, 0);
        assertThat(result.trades()).hasSize(1);
        BacktestTrade t = result.trades().get(0);
        assertThat(t.exitReason()).isEqualTo(ExitReason.FORCE_EXIT);
        assertThat(t.exitTime()).isEqualTo(DAY.atTime(15, 10).atZone(IST).toInstant());
        assertThat(t.exitPrice()).isEqualByComparingTo("105.00");
        // max_trades_per_day = 1: no second entry even though the close stays above the range
        assertThat(result.overall().totalTrades()).isEqualTo(1);
    }

    @Test
    void slippageAndBarCloseFill() {
        List<Ohlc> after = List.of(SyntheticSessions.bar("103.5", "104", "103", "103.8"), SyntheticSessions.bar("103.8", "111", "103.5", "110"));
        BacktestResult next = run(breakoutDay(after), FillModel.NEXT_OPEN, 10);
        // 103.50 * 1.001 = 103.6035 -> rounded up to the tick: 103.65
        assertThat(next.trades().get(0).entryPrice()).isEqualByComparingTo("103.65");
        BacktestResult close = run(breakoutDay(after), FillModel.BAR_CLOSE, 0);
        assertThat(close.trades().get(0).entryPrice()).isEqualByComparingTo("103.00");
        assertThat(close.trades().get(0).entryTime()).isEqualTo(DAY.atTime(9, 35).atZone(IST).toInstant());
        assertThat(close.trades().get(0).target()).isEqualByComparingTo("109.00");
    }

    @Test
    void sameInputSameHash() {
        List<Candle> day = breakoutDay(List.of(SyntheticSessions.bar("103.5", "104", "103", "103.8"), SyntheticSessions.bar("103.8", "111", "103.5", "110")));
        BacktestResult a = run(day, FillModel.NEXT_OPEN, 5);
        BacktestResult b = run(day, FillModel.NEXT_OPEN, 5);
        assertThat(a.resultHash()).isEqualTo(b.resultHash()).hasSize(64);
        assertThat(a.trades().get(0).netPnl()).isEqualTo(b.trades().get(0).netPnl());
        BacktestResult c = run(day, FillModel.NEXT_OPEN, 0);
        assertThat(c.resultHash()).isNotEqualTo(a.resultHash());
    }

    @Test
    void noTradeWithoutBreakoutAndBothDirectionRejected() {
        List<Candle> candles = new ArrayList<>(SyntheticSessions.flat(INSTRUMENT, WARMUP, "101"));
        candles.addAll(SyntheticSessions.flat(INSTRUMENT, DAY, "101"));
        BacktestResult result = engine.run(new BacktestInput(orb, spec(UUID.randomUUID(), DAY, DAY, FillModel.NEXT_OPEN, 0), Map.of(INSTRUMENT, META),
                Map.of(INSTRUMENT, candles), Money.ofRupees(2000)));
        assertThat(result.trades()).isEmpty();
        assertThat(result.overall().totalTrades()).isZero();
        assertThat(result.warnings()).extracting(QualityWarning::code).contains("INSUFFICIENT_SAMPLE");

        StrategyDefinition both = new DefinitionParser().parse(ORB.replace("direction: long", "direction: both")
                .replace("type: opening_range_low", "type: percent\n  value: 1"));
        assertThatThrownBy(() -> engine.run(new BacktestInput(both, spec(UUID.randomUUID(), DAY, DAY, FillModel.NEXT_OPEN, 0), Map.of(INSTRUMENT, META),
                Map.of(INSTRUMENT, candles), Money.ofRupees(2000)))).isInstanceOf(BacktestException.class).hasMessageContaining("both");
    }

    @Test
    void endOfDataClosesOpenTrade() {
        List<Candle> day = breakoutDay(List.of(SyntheticSessions.bar("103.5", "104", "103", "103.8"), SyntheticSessions.bar("103.8", "104", "103.5", "104")));
        List<Candle> candles = new ArrayList<>(SyntheticSessions.flat(INSTRUMENT, WARMUP, "101"));
        candles.addAll(SyntheticSessions.truncated(day, 6));
        BacktestResult result = engine.run(new BacktestInput(orb, spec(UUID.randomUUID(), DAY, DAY, FillModel.NEXT_OPEN, 0), Map.of(INSTRUMENT, META),
                Map.of(INSTRUMENT, candles), Money.ofRupees(2000)));
        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).exitReason()).isEqualTo(ExitReason.END_OF_DATA);
        assertThat(result.trades().get(0).exitPrice()).isEqualByComparingTo("104.00");
    }
}
