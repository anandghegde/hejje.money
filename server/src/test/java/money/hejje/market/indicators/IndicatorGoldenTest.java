package money.hejje.market.indicators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withPrecision;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import money.hejje.common.Timeframe;
import money.hejje.strategy.dsl.ConditionParser;
import money.hejje.strategy.dsl.Expr;
import org.junit.jupiter.api.Test;

/**
 * Every indicator against the TA-Lib/pandas reference values in {@code indicators/golden.csv}: same value within 1e-6
 * on every bar, and not ready exactly where the reference has no value.
 */
class IndicatorGoldenTest {

    static final double TOLERANCE = 1e-6;

    /** CSV column -> canonical DSL call. */
    static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("sma_20", "sma(20)"),
            Map.entry("ema_20", "ema(20)"),
            Map.entry("rsi_14", "rsi(14)"),
            Map.entry("atr_14", "atr(14)"),
            Map.entry("bb_upper_20_2", "bb_upper(20, 2)"),
            Map.entry("bb_lower_20_2", "bb_lower(20, 2)"),
            Map.entry("adx_14", "adx(14)"),
            Map.entry("highest_20", "highest(20)"),
            Map.entry("lowest_20", "lowest(20)"),
            Map.entry("vwap", "vwap"),
            Map.entry("or_high_15", "opening_range_high(15m)"),
            Map.entry("or_low_15", "opening_range_low(15m)"),
            Map.entry("relvol_20", "relative_volume(20)"),
            Map.entry("prev_day_high", "prev_day_high"),
            Map.entry("prev_day_low", "prev_day_low"),
            Map.entry("prev_day_close", "prev_day_close"),
            Map.entry("gap_pct", "gap_pct"),
            Map.entry("session_minutes", "session_minutes"),
            Map.entry("session_open", "session_open"),
            Map.entry("session_high", "session_high"),
            Map.entry("session_low", "session_low"),
            Map.entry("pivot", "pivot"),
            Map.entry("cpr_top", "cpr_top"),
            Map.entry("cpr_bottom", "cpr_bottom"),
            Map.entry("cpr_width_pct", "cpr_width_pct"),
            Map.entry("supertrend_10_3", "supertrend(10, 3)"),
            Map.entry("prev_day_nr_4", "prev_day_nr(4)"),
            Map.entry("prev_day_nr_7", "prev_day_nr(7)"),
            Map.entry("opening_return_30", "opening_return(30m)"));

    @Test
    void everyIndicatorMatchesTheReference() {
        List<Fixtures.Row> rows = Fixtures.golden();
        assertThat(rows).hasSize(900);
        IndicatorContext ctx = new IndicatorContext(Timeframe.M5, Fixtures.IST);
        for (String call : COLUMNS.values()) {
            Expr.IndicatorCall parsed = (Expr.IndicatorCall) ConditionParser.parseExpr(call);
            ctx.register(parsed.name(), parsed.args());
        }
        assertThat(ctx.registered()).hasSize(COLUMNS.size());

        int checked = 0;
        for (int i = 0; i < rows.size(); i++) {
            Fixtures.Row row = rows.get(i);
            ctx.onCandleClosed(row.candle());
            for (Map.Entry<String, String> column : COLUMNS.entrySet()) {
                double expected = row.expected().get(column.getKey());
                Expr.IndicatorCall parsed = (Expr.IndicatorCall) ConditionParser.parseExpr(column.getValue());
                OptionalDouble actual = ctx.indicator(parsed.name(), parsed.args(), 0);
                String where = column.getKey() + " at bar " + i + " (" + row.candle().openTime() + ")";
                if (Double.isNaN(expected)) {
                    assertThat(actual).as(where + " should not be ready").isEmpty();
                } else {
                    assertThat(actual).as(where).isPresent();
                    assertThat(actual.getAsDouble()).as(where).isEqualTo(expected, withPrecision(TOLERANCE));
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(5000);
    }

    @Test
    void offsetsLookBackThroughHistory() {
        List<Fixtures.Row> rows = Fixtures.golden();
        IndicatorContext ctx = new IndicatorContext(Timeframe.M5, Fixtures.IST);
        ctx.register("ema", List.of(new Expr.Arg.Number(20)));
        ctx.warmUp(rows.stream().map(Fixtures.Row::candle).toList());
        for (int back = 0; back < 5; back++) {
            double expected = rows.get(rows.size() - 1 - back).expected().get("ema_20");
            assertThat(ctx.indicator("ema", List.of(new Expr.Arg.Number(20)), back).getAsDouble()).isEqualTo(expected, withPrecision(TOLERANCE));
            assertThat(ctx.series("close", back).getAsDouble())
                    .isEqualTo(rows.get(rows.size() - 1 - back).candle().close().doubleValue(), withPrecision(TOLERANCE));
        }
        // before the first bar there is nothing
        assertThat(ctx.series("close", rows.size())).isEmpty();
    }
}
