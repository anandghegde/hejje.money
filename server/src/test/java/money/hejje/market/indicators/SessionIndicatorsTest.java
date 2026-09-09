package money.hejje.market.indicators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.withPrecision;

import java.time.Duration;
import java.util.List;
import money.hejje.common.Timeframe;
import money.hejje.strategy.dsl.Condition;
import money.hejje.strategy.dsl.ConditionEvaluator;
import money.hejje.strategy.dsl.ConditionParser;
import money.hejje.strategy.dsl.EvalStatus;
import money.hejje.strategy.dsl.Expr.Arg;
import org.junit.jupiter.api.Test;

class SessionIndicatorsTest {

    static final List<Arg> FIFTEEN = List.of(new Arg.DurationArg(Duration.ofMinutes(15)));

    private static IndicatorContext context() {
        return new IndicatorContext(Timeframe.M5, Fixtures.IST);
    }

    @Test
    void openingRangeIsNotReadyBefore0930ThenConstant() {
        IndicatorContext ctx = context();
        ctx.register("opening_range_high", FIFTEEN);
        ctx.register("opening_range_low", FIFTEEN);
        Condition breakout = ConditionParser.parse("close > opening_range_high(15m)");

        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:15", "100", "105", "99", "104", 1000));
        assertThat(ctx.indicator("opening_range_high", FIFTEEN, 0)).isEmpty();
        assertThat(ConditionEvaluator.evaluate(breakout, ctx).status()).isEqualTo(EvalStatus.NOT_READY);
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:20", "104", "107", "103", "106", 1000));
        assertThat(ctx.indicator("opening_range_high", FIFTEEN, 0)).isEmpty();
        // the 09:25 bar closes at 09:30: range complete
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:25", "106", "106.5", "101", "102", 1000));
        assertThat(ctx.indicator("opening_range_high", FIFTEEN, 0).getAsDouble()).isEqualTo(107.0);
        assertThat(ctx.indicator("opening_range_low", FIFTEEN, 0).getAsDouble()).isEqualTo(99.0);
        // later bars do not move it
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:30", "102", "120", "90", "110", 1000));
        assertThat(ctx.indicator("opening_range_high", FIFTEEN, 0).getAsDouble()).isEqualTo(107.0);
        assertThat(ctx.indicator("opening_range_low", FIFTEEN, 0).getAsDouble()).isEqualTo(99.0);
        assertThat(ConditionEvaluator.evaluate(breakout, ctx).status()).isEqualTo(EvalStatus.PASSED);
        // previous bar's view is also available
        assertThat(ctx.indicator("opening_range_high", FIFTEEN, 1).getAsDouble()).isEqualTo(107.0);
        assertThat(ctx.indicator("opening_range_high", FIFTEEN, 3)).isEmpty();
        // next session resets
        ctx.onCandleClosed(Fixtures.candle("2026-09-09T09:15", "111", "112", "110", "111", 1000));
        assertThat(ctx.indicator("opening_range_high", FIFTEEN, 0)).isEmpty();
    }

    @Test
    void vwapResetsPerSessionAndNeedsVolume() {
        IndicatorContext ctx = context();
        ctx.register("vwap", List.of());
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:15", "100", "110", "90", "100", 100)); // tp 100
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:20", "100", "130", "110", "120", 300)); // tp 120
        assertThat(ctx.indicator("vwap", List.of(), 0).getAsDouble()).isEqualTo(115.0, withPrecision(1e-9));
        ctx.onCandleClosed(Fixtures.candle("2026-09-09T09:15", "200", "210", "190", "200", 50));
        assertThat(ctx.indicator("vwap", List.of(), 0).getAsDouble()).isEqualTo(200.0, withPrecision(1e-9));
        // an index series (no volume) has no VWAP
        IndicatorContext index = context();
        index.register("vwap", List.of());
        index.onCandleClosed(Fixtures.candle("2026-09-08T09:15", "100", "110", "90", "100", 0));
        assertThat(index.indicator("vwap", List.of(), 0)).isEmpty();
    }

    @Test
    void previousDayGapAndSessionMinutes() {
        IndicatorContext ctx = context();
        for (String name : List.of("prev_day_high", "prev_day_low", "prev_day_close", "gap_pct", "session_minutes")) {
            ctx.register(name, List.of());
        }
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:15", "100", "105", "99", "104", 10));
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T15:25", "104", "108", "97", "101", 10));
        assertThat(ctx.indicator("prev_day_high", List.of(), 0)).isEmpty();
        assertThat(ctx.indicator("gap_pct", List.of(), 0)).isEmpty();
        assertThat(ctx.indicator("session_minutes", List.of(), 0).getAsDouble()).isEqualTo(375.0);
        ctx.onCandleClosed(Fixtures.candle("2026-09-09T09:15", "103.02", "104", "103", "103.5", 10));
        assertThat(ctx.indicator("prev_day_high", List.of(), 0).getAsDouble()).isEqualTo(108.0);
        assertThat(ctx.indicator("prev_day_low", List.of(), 0).getAsDouble()).isEqualTo(97.0);
        assertThat(ctx.indicator("prev_day_close", List.of(), 0).getAsDouble()).isEqualTo(101.0);
        assertThat(ctx.indicator("gap_pct", List.of(), 0).getAsDouble()).isEqualTo(2.0, withPrecision(1e-9));
        assertThat(ctx.indicator("session_minutes", List.of(), 0).getAsDouble()).isEqualTo(5.0);
        // seeding from a daily candle when warm-up history starts mid-way
        IndicatorContext seeded = context();
        seeded.register("prev_day_close", List.of());
        seeded.session().seedPreviousDay(java.time.LocalDate.of(2026, 9, 8), 108, 97, 101);
        seeded.onCandleClosed(Fixtures.candle("2026-09-09T09:15", "103", "104", "103", "103.5", 10));
        assertThat(seeded.indicator("prev_day_close", List.of(), 0).getAsDouble()).isEqualTo(101.0);
    }

    @Test
    void relativeVolumeUsesPriorSessionsOfTheSameSlot() {
        IndicatorContext ctx = context();
        List<Arg> two = List.of(new Arg.Number(2));
        ctx.register("relative_volume", two);
        ctx.onCandleClosed(Fixtures.candle("2026-09-07T09:15", "1", "1", "1", "1", 100));
        assertThat(ctx.indicator("relative_volume", two, 0)).isEmpty();
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:15", "1", "1", "1", "1", 300));
        assertThat(ctx.indicator("relative_volume", two, 0).getAsDouble()).isEqualTo(3.0, withPrecision(1e-9));
        ctx.onCandleClosed(Fixtures.candle("2026-09-09T09:15", "1", "1", "1", "1", 400));
        assertThat(ctx.indicator("relative_volume", two, 0).getAsDouble()).isEqualTo(2.0, withPrecision(1e-9)); // mean(100,300)
        ctx.onCandleClosed(Fixtures.candle("2026-09-10T09:15", "1", "1", "1", "1", 700));
        assertThat(ctx.indicator("relative_volume", two, 0).getAsDouble()).isEqualTo(2.0, withPrecision(1e-9)); // mean(300,400): only 2 sessions
        // a different slot has its own baseline
        ctx.onCandleClosed(Fixtures.candle("2026-09-10T09:20", "1", "1", "1", "1", 700));
        assertThat(ctx.indicator("relative_volume", two, 0)).isEmpty();
    }

    @Test
    void contextGuardsOrderAndTimeframe() {
        IndicatorContext ctx = context();
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:20", "1", "1", "1", "1", 1));
        assertThatThrownBy(() -> ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:15", "1", "1", "1", "1", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ctx.barCount()).isEqualTo(1);
        // an indicator asked for before registration is registered and not ready
        assertThat(ctx.indicator("ema", List.of(new Arg.Number(3)), 0)).isEmpty();
        assertThat(ctx.registered()).contains("ema(3)");
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:25", "2", "2", "2", "2", 1));
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:30", "3", "3", "3", "3", 1));
        ctx.onCandleClosed(Fixtures.candle("2026-09-08T09:35", "4", "4", "4", "4", 1));
        assertThat(ctx.indicator("ema", List.of(new Arg.Number(3)), 0)).isPresent();
        assertThat(ctx.isReady()).isTrue();
    }

    @Test
    void registerDefinitionCollectsEveryCall() {
        money.hejje.strategy.StrategyDefinition def = new money.hejje.strategy.internal.DefinitionParser().parse("""
                name: test_def
                universe: [NSE:INFY]
                timeframe: 5m
                direction: long
                entry:
                  all:
                    - close > opening_range_high
                    - close > vwap + 0.5 * atr(14)
                exit:
                  any:
                    - rsi(14) > 70
                stop:
                  type: opening_range_low
                """);
        IndicatorContext ctx = context();
        ctx.registerDefinition(def);
        assertThat(ctx.registered()).containsExactly("atr(14)", "opening_range_high(15m)", "rsi(14)", "vwap");
    }
}
