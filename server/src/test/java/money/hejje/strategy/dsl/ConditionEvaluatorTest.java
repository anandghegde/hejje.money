package money.hejje.strategy.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import money.hejje.strategy.dsl.Expr.Arg;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

    /** Fake bar context keyed by "name[offset]" for series and "name(args)[offset]" for indicators. */
    static final class FakeContext implements BarContext {
        final Map<String, Double> values = new HashMap<>();

        FakeContext put(String key, double value) {
            values.put(key, value);
            return this;
        }

        @Override
        public OptionalDouble series(String name, int offset) {
            Double v = values.get(name + "[" + offset + "]");
            return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
        }

        @Override
        public OptionalDouble indicator(String name, List<Arg> args, int offset) {
            Double v = values.get(new Expr.IndicatorCall(name, args, offset).text() + (offset == 0 ? "[0]" : ""));
            return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
        }
    }

    @Test
    void comparesAndReportsObservedValues() {
        FakeContext ctx = new FakeContext().put("close[0]", 101).put("vwap[0]", 100);
        EvalResult r = ConditionEvaluator.evaluate(ConditionParser.parse("close > vwap"), ctx);
        assertThat(r.status()).isEqualTo(EvalStatus.PASSED);
        assertThat(r.observedLhs()).isEqualTo(101);
        assertThat(r.observedRhs()).isEqualTo(100);
        assertThat(r.condition()).isEqualTo("close > vwap");

        assertThat(ConditionEvaluator.evaluate(ConditionParser.parse("close < vwap"), ctx).status()).isEqualTo(EvalStatus.FAILED);
        assertThat(ConditionEvaluator.evaluate(ConditionParser.parse("close >= 101"), ctx).status()).isEqualTo(EvalStatus.PASSED);
        assertThat(ConditionEvaluator.evaluate(ConditionParser.parse("close <= 100.99"), ctx).status()).isEqualTo(EvalStatus.FAILED);
        assertThat(ConditionEvaluator.evaluate(ConditionParser.parse("close == 101"), ctx).status()).isEqualTo(EvalStatus.PASSED);
    }

    @Test
    void arithmeticFollowsPrecedence() {
        FakeContext ctx = new FakeContext().put("close[0]", 110).put("open[0]", 100).put("atr(14)[0]", 4);
        // 100 + 2*4 = 108 < 110
        assertThat(ConditionEvaluator.evaluate(ConditionParser.parse("close > open + 2 * atr(14)"), ctx).passed()).isTrue();
        // (100 + 2) * 4 = 408 > 110
        assertThat(ConditionEvaluator.evaluate(ConditionParser.parse("close > (open + 2) * atr(14)"), ctx).passed()).isFalse();
        EvalResult r = ConditionEvaluator.evaluate(ConditionParser.parse("(close - open) / open > 0.05"), ctx);
        assertThat(r.passed()).isTrue();
        assertThat(r.observedLhs()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void missingOperandIsNotReadyNeverTrue() {
        FakeContext ctx = new FakeContext().put("close[0]", 101);
        EvalResult r = ConditionEvaluator.evaluate(ConditionParser.parse("close > ema(20)"), ctx);
        assertThat(r.status()).isEqualTo(EvalStatus.NOT_READY);
        assertThat(r.passed()).isFalse();
        assertThat(r.observedLhs()).isNull();
        // division by zero is not ready either
        ctx.put("open[0]", 0);
        assertThat(ConditionEvaluator.evaluate(ConditionParser.parse("close / open > 1"), ctx).status()).isEqualTo(EvalStatus.NOT_READY);
    }

    @Test
    void crossesAboveUsesPreviousBar() {
        Condition cross = ConditionParser.parse("close crosses_above ema(20)");
        FakeContext ctx = new FakeContext().put("close[0]", 101).put("ema(20)[0]", 100).put("close[1]", 99).put("ema(20)[1]", 100);
        assertThat(ConditionEvaluator.evaluate(cross, ctx).status()).isEqualTo(EvalStatus.PASSED);
        // already above on the previous bar: no cross
        ctx.put("close[1]", 100.5);
        assertThat(ConditionEvaluator.evaluate(cross, ctx).status()).isEqualTo(EvalStatus.FAILED);
        // previous bar unknown: not ready
        ctx.values.remove("close[1]");
        assertThat(ConditionEvaluator.evaluate(cross, ctx).status()).isEqualTo(EvalStatus.NOT_READY);

        Condition below = ConditionParser.parse("close crosses_below ema(20)");
        FakeContext down = new FakeContext().put("close[0]", 99).put("ema(20)[0]", 100).put("close[1]", 100).put("ema(20)[1]", 100);
        assertThat(ConditionEvaluator.evaluate(below, down).status()).isEqualTo(EvalStatus.PASSED);
    }

    @Test
    void offsetsShiftWithCrossEvaluation() {
        // ema(20)[1] crosses_above ema(50)[1] needs offsets 1 and 2
        Condition c = ConditionParser.parse("ema(20)[1] crosses_above ema(50)[1]");
        FakeContext ctx = new FakeContext().put("ema(20)[1]", 10).put("ema(50)[1]", 9).put("ema(20)[2]", 8).put("ema(50)[2]", 9);
        assertThat(ConditionEvaluator.evaluate(c, ctx).status()).isEqualTo(EvalStatus.PASSED);
    }
}
