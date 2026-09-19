package money.hejje.strategy.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import money.hejje.strategy.dsl.Expr.Arg;
import money.hejje.strategy.dsl.Expr.Binary;
import money.hejje.strategy.dsl.Expr.IndicatorCall;
import money.hejje.strategy.dsl.Expr.NumberLiteral;
import money.hejje.strategy.dsl.Expr.SeriesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Golden tests: input text to canonical text (parenthesised by precedence, defaults filled in). */
class ConditionParserTest {

    static Stream<Arguments> golden() {
        return Stream.of(
                Arguments.of("close > opening_range_high", "close > opening_range_high(15m)"),
                Arguments.of("close > opening_range_high(15m)", "close > opening_range_high(15m)"),
                Arguments.of("close > opening_range_high(30m)", "close > opening_range_high(30m)"),
                Arguments.of("close < opening_range_low(1h)", "close < opening_range_low(1h)"),
                Arguments.of("close > vwap", "close > vwap"),
                Arguments.of("close>vwap", "close > vwap"),
                Arguments.of("relative_volume > 1.4", "relative_volume(20) > 1.4"),
                Arguments.of("relative_volume(10) >= 1.5", "relative_volume(10) >= 1.5"),
                Arguments.of("ema(20) > sma(50)", "ema(20) > sma(50)"),
                Arguments.of("rsi(14) < 30", "rsi(14) < 30"),
                Arguments.of("rsi(14) <= 30.5", "rsi(14) <= 30.5"),
                Arguments.of("close == prev_day_close", "close == prev_day_close"),
                Arguments.of("close crosses_above ema(20)", "close crosses_above ema(20)"),
                Arguments.of("ema(9) crosses_below ema(21)", "ema(9) crosses_below ema(21)"),
                Arguments.of("close[1] > close[2]", "close[1] > close[2]"),
                Arguments.of("ema(20)[1] < ema(20)", "ema(20)[1] < ema(20)"),
                Arguments.of("high > prev_day_high", "high > prev_day_high"),
                Arguments.of("low < prev_day_low", "low < prev_day_low"),
                Arguments.of("close > bb_upper(20, 2)", "close > bb_upper(20, 2)"),
                Arguments.of("close < bb_lower(20,2.5)", "close < bb_lower(20, 2.5)"),
                Arguments.of("adx(14) > 25", "adx(14) > 25"),
                Arguments.of("gap_pct > 0.5", "gap_pct > 0.5"),
                Arguments.of("session_minutes >= 15", "session_minutes >= 15"),
                Arguments.of("session_low == session_open", "session_low == session_open"),
                Arguments.of("session_high == session_open", "session_high == session_open"),
                Arguments.of("cpr_width_pct < 0.2", "cpr_width_pct < 0.2"),
                Arguments.of("close crosses_above cpr_top", "close crosses_above cpr_top"),
                Arguments.of("close < cpr_bottom", "close < cpr_bottom"),
                Arguments.of("close > pivot", "close > pivot"),
                Arguments.of("close crosses_above supertrend(10,3)", "close crosses_above supertrend(10, 3)"),
                Arguments.of("prev_day_nr(7) == 1", "prev_day_nr(7) == 1"),
                Arguments.of("opening_return(30m) > 0", "opening_return(30m) > 0"),
                Arguments.of("volume > relative_volume * 2", "volume > (relative_volume(20) * 2)"),
                // arithmetic precedence: * and / bind tighter than + and -
                Arguments.of("close > open + 2 * atr(14)", "close > (open + (2 * atr(14)))"),
                Arguments.of("close > (open + 2) * atr(14)", "close > ((open + 2) * atr(14))"),
                Arguments.of("close - open > atr(14) / 2", "(close - open) > (atr(14) / 2)"),
                Arguments.of("close > a_b_c_dummy_test_never", null), // placeholder replaced below
                Arguments.of("close - open - 1 > 0", "((close - open) - 1) > 0"),
                Arguments.of("close / open / 2 > 0", "((close / open) / 2) > 0"),
                Arguments.of("close > -atr(14) + vwap", "close > (-atr(14) + vwap)"),
                Arguments.of("-close < 0", "-close < 0"),
                Arguments.of("(close - vwap) / vwap > 0.002", "((close - vwap) / vwap) > 0.002"),
                Arguments.of("close > highest(20)[1]", "close > highest(20)[1]"),
                Arguments.of("close < lowest(20)[1]", "close < lowest(20)[1]"),
                Arguments.of("  close   >   vwap  ", "close > vwap"),
                Arguments.of("close > 100.0", "close > 100"),
                Arguments.of("close > .5", "close > 0.5")
        ).filter(a -> a.get()[1] != null);
    }

    @ParameterizedTest
    @MethodSource("golden")
    void parsesToCanonicalText(String input, String expected) {
        Condition condition = ConditionParser.parse(input);
        assertThat(condition.text()).isEqualTo(expected);
        // canonical text re-parses to itself
        assertThat(ConditionParser.parse(condition.text()).text()).isEqualTo(expected);
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of("", "Empty condition"),
                Arguments.of("close", "Expected a comparison operator"),
                Arguments.of("close > ", "Unexpected end of condition"),
                Arguments.of("> close", "Unexpected '>'"),
                Arguments.of("close > vwap > 1", "Unexpected '>'"),
                Arguments.of("close != vwap", "'!=' is not supported"),
                Arguments.of("close = vwap", "Use '==' for equality"),
                Arguments.of("close > foo(20)", "Unknown indicator 'foo'"),
                Arguments.of("close > ema", "'ema' takes 1 argument(s) but got 0"),
                Arguments.of("close > ema(20, 30)", "'ema' takes 1 argument(s) but got 2"),
                Arguments.of("close > ema(0)", "Argument 1 of 'ema' must be a positive whole number"),
                Arguments.of("close > ema(2.5)", "Argument 1 of 'ema' must be a positive whole number"),
                Arguments.of("close > bb_upper(20)", "'bb_upper' takes 2 argument(s) but got 1"),
                Arguments.of("close > opening_range_high(15)", "Argument 1 of 'opening_range_high' must be a duration"),
                Arguments.of("close > opening_range_high(15m, 2)", "'opening_range_high' takes 0 to 1 argument(s) but got 2"),
                Arguments.of("close > supertrend(10)", "'supertrend' takes 2 argument(s) but got 1"),
                Arguments.of("opening_return > 0", "'opening_return' takes 1 argument(s) but got 0"),
                Arguments.of("opening_return(30) > 0", "Argument 1 of 'opening_return' must be a duration"),
                Arguments.of("prev_day_nr(7m) == 1", "Argument 1 of 'prev_day_nr' must be a positive whole number"),
                Arguments.of("close > ema(20", "Expected ')'"),
                Arguments.of("close > (open + 1", "Expected ')'"),
                Arguments.of("close[1 > open", "Expected ']'"),
                Arguments.of("close[1.5] > open", "Bar offset must be a whole number"),
                Arguments.of("close(1) > open", "'close' is a series and takes no arguments"),
                Arguments.of("Close > vwap", "Identifiers are lower case"),
                Arguments.of("close > 1.2.3", "Malformed number"),
                Arguments.of("close > 15m", "A duration is only valid as an indicator argument"),
                Arguments.of("close > vwap & open > 1", "Unexpected character '&'"),
                Arguments.of("close crosses_above", "Unexpected end of condition"),
                Arguments.of("crosses_above > 1", "Unexpected 'crosses_above'"),
                Arguments.of("close > 12abc", "Malformed number")
        );
    }

    @ParameterizedTest
    @MethodSource("errors")
    void rejectsWithPosition(String input, String messageFragment) {
        assertThatThrownBy(() -> ConditionParser.parse(input))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining(messageFragment)
                .hasMessageContaining("at position");
    }

    @Test
    void buildsExpectedAst() {
        Condition c = ConditionParser.parse("close > open + 2 * atr(14)");
        assertThat(c.lhs()).isEqualTo(new SeriesRef("close", 0));
        assertThat(c.op()).isEqualTo(CompareOp.GT);
        assertThat(c.rhs()).isInstanceOf(Binary.class);
        Binary sum = (Binary) c.rhs();
        assertThat(sum.op()).isEqualTo(Expr.ArithOp.ADD);
        assertThat(sum.left()).isEqualTo(new SeriesRef("open", 0));
        Binary product = (Binary) sum.right();
        assertThat(product.left()).isEqualTo(new NumberLiteral(2));
        assertThat(product.right()).isEqualTo(new IndicatorCall("atr", List.of(new Arg.Number(14)), 0));
    }

    @Test
    void fillsDefaultDurationArgument() {
        Condition c = ConditionParser.parse("close > opening_range_high");
        IndicatorCall call = (IndicatorCall) c.rhs();
        assertThat(call.args()).containsExactly(new Arg.DurationArg(Duration.ofMinutes(15)));
    }

    @Test
    void errorPositionPointsAtTheProblem() {
        ParseException e = (ParseException) org.assertj.core.api.Assertions.catchThrowable(() -> ConditionParser.parse("close > foo(1)"));
        assertThat(e.position()).isEqualTo(8);
    }
}
