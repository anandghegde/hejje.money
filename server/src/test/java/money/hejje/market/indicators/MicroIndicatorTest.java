package money.hejje.market.indicators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import money.hejje.common.Timeframe;
import money.hejje.market.BarMicro;
import money.hejje.market.Candle;
import money.hejje.strategy.dsl.Condition;
import money.hejje.strategy.dsl.ConditionEvaluator;
import money.hejje.strategy.dsl.ConditionParser;
import money.hejje.strategy.dsl.EvalStatus;
import money.hejje.strategy.dsl.Expr.Arg;
import org.junit.jupiter.api.Test;

/** Golden values of the order-book and flow indicators, and NOT_READY without micro data (plan M9.4). */
class MicroIndicatorTest {

    static BarMicro micro(Candle c, Double imbClose, Double imbMean, Double ratio, long up, long down) {
        return new BarMicro(c.instrumentId(), c.timeframe(), c.openTime(), imbClose, imbMean, ratio, up + down == 0 ? null : (double) up / (up + down), up,
                down, 10, 10);
    }

    @Test
    void valuesComeFromTheBarsMicroData() {
        IndicatorContext ctx = new IndicatorContext(Timeframe.M5, Fixtures.IST);
        ctx.register("book_imbalance", List.of());
        ctx.register("book_imbalance_mean", List.of());
        ctx.register("buy_sell_qty_ratio", List.of());
        ctx.register("flow_up_share", List.of(new Arg.Number(2)));
        Candle a = Fixtures.candle("2026-09-08T09:15", "100", "101", "99", "100.5", 1000);
        Candle b = Fixtures.candle("2026-09-08T09:20", "100.5", "102", "100", "101.5", 1000);
        ctx.onCandleClosed(a, micro(a, 0.2, 0.1, 1.2, 300, 100));
        assertThat(ctx.indicator("book_imbalance", List.of(), 0).getAsDouble()).isEqualTo(0.2);
        assertThat(ctx.indicator("book_imbalance_mean", List.of(), 0).getAsDouble()).isEqualTo(0.1);
        assertThat(ctx.indicator("buy_sell_qty_ratio", List.of(), 0).getAsDouble()).isEqualTo(1.2);
        assertThat(ctx.indicator("flow_up_share", List.of(new Arg.Number(2)), 0)).isEmpty(); // needs 2 bars
        ctx.onCandleClosed(b, micro(b, -0.3, -0.05, 0.9, 100, 300));
        // (300 + 100) / (300 + 100 + 100 + 300)
        assertThat(ctx.indicator("flow_up_share", List.of(new Arg.Number(2)), 0).getAsDouble()).isCloseTo(0.5, within(1e-12));
        assertThat(ConditionEvaluator.evaluate(ConditionParser.parse("book_imbalance < 0"), ctx).status()).isEqualTo(EvalStatus.PASSED);
    }

    @Test
    void aBarWithoutMicroDataIsNotReady() {
        IndicatorContext ctx = new IndicatorContext(Timeframe.M5, Fixtures.IST);
        Condition rule = ConditionParser.parse("book_imbalance > 0.2");
        Condition flow = ConditionParser.parse("flow_up_share(2) > 0.6");
        ctx.register("book_imbalance", List.of());
        ctx.register("flow_up_share", List.of(new Arg.Number(2)));
        Candle a = Fixtures.candle("2026-09-08T09:15", "100", "101", "99", "100.5", 1000);
        Candle b = Fixtures.candle("2026-09-08T09:20", "100.5", "102", "100", "101.5", 1000);
        Candle c = Fixtures.candle("2026-09-08T09:25", "101.5", "103", "101", "102.5", 1000);
        ctx.onCandleClosed(a, micro(a, 0.5, 0.5, 1.0, 900, 100));
        ctx.onCandleClosed(b); // candle history: no micro
        assertThat(ConditionEvaluator.evaluate(rule, ctx).status()).isEqualTo(EvalStatus.NOT_READY);
        assertThat(ConditionEvaluator.evaluate(flow, ctx).status()).isEqualTo(EvalStatus.NOT_READY);
        ctx.onCandleClosed(c, micro(c, 0.5, 0.5, 1.0, 900, 100));
        assertThat(ConditionEvaluator.evaluate(rule, ctx).status()).isEqualTo(EvalStatus.PASSED);
        // the 2-bar window still holds the bar without micro data
        assertThat(ConditionEvaluator.evaluate(flow, ctx).status()).isEqualTo(EvalStatus.NOT_READY);
    }
}
