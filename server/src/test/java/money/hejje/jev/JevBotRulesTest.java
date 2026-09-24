package money.hejje.jev;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.llm.JevAnswer;
import money.hejje.llm.JevResult;
import money.hejje.market.BarMicro;
import money.hejje.market.Candle;
import org.junit.jupiter.api.Test;

/** The Jev bot's rules and state on hand-built answers and bars (plan M9.5). Thresholds from the repo's question sets. */
class JevBotRulesTest {

    static JsonNode params(String set) throws Exception {
        return new ObjectMapper(new YAMLFactory()).readTree(new File("../config/jev/" + set + ".yaml")).path("params");
    }

    static JevResult result(Map<String, JevAnswer> answers) {
        return new JevResult(true, JevResult.Outcome.OK, answers, 5, 100, "fixture", UUID.randomUUID(), null);
    }

    static JevAnswer noul(String k, double p) {
        return new JevAnswer(k, "noul", null, null, p, Map.of(), null);
    }

    static JevAnswer choice(String k, String c, Map<String, Double> p, Double conf) {
        return new JevAnswer(k, "choice", c, null, null, p, conf);
    }

    static JevAnswer score(String k, double s) {
        return new JevAnswer(k, "score", null, s, null, Map.of(), 0.8);
    }

    @Test
    void stage1KeepsTheTopThreePerSideAboveTheBarAndRespectsTheRegime() throws Exception {
        List<String> symbols = List.of("NSE:A", "NSE:B", "NSE:C", "NSE:D", "NSE:E");
        Map<String, JevAnswer> a = new LinkedHashMap<>();
        double[] longs = {0.9, 0.35, 0.6, 0.7, 0.45};
        double[] shorts = {0.1, 0.8, 0.2, 0.1, 0.5};
        for (int i = 0; i < 5; i++) {
            a.put("long_" + i, noul("long_" + i, longs[i]));
            a.put("short_" + i, noul("short_" + i, shorts[i]));
        }
        a.put("regime", choice("regime", "range", Map.of("range", 1.0), 0.9));
        List<JevBotRules.Candidate> c = JevBotRules.stage1(result(a), symbols, params("bot-stage1"));
        assertThat(c).extracting(JevBotRules.Candidate::symbol).containsExactly("NSE:A", "NSE:D", "NSE:C", "NSE:B", "NSE:E");
        assertThat(c.get(3).longSide()).isFalse();

        a.put("regime", choice("regime", "trend_up", Map.of("trend_up", 1.0), 0.9));
        assertThat(JevBotRules.stage1(result(a), symbols, params("bot-stage1"))).allMatch(JevBotRules.Candidate::longSide);
        a.put("regime", choice("regime", "trend_down", Map.of("trend_down", 1.0), 0.9));
        assertThat(JevBotRules.stage1(result(a), symbols, params("bot-stage1"))).noneMatch(JevBotRules.Candidate::longSide);
    }

    static Map<String, JevAnswer> stage2(String setup, double p, Double conf, double tq, double flow, double idx, double liq) {
        Map<String, JevAnswer> a = new LinkedHashMap<>();
        a.put("setup", choice("setup", setup, Map.of(setup, p), conf));
        a.put("trend_quality", score("trend_quality", tq));
        a.put("flow_alignment", score("flow_alignment", flow));
        a.put("index_alignment", score("index_alignment", idx));
        a.put("liquidity", score("liquidity", liq));
        return a;
    }

    static final Map<String, Integer> LEVELS = Map.of("trend_quality", 4, "flow_alignment", 4, "index_alignment", 3, "liquidity", 3);

    @Test
    void stage2EntersOnlyWhenEveryConditionHolds() throws Exception {
        JsonNode p = params("bot-stage2");
        JevBotRules.Entry ok = JevBotRules.stage2(result(stage2("long_continuation", 0.7, 0.6, 2, 2, 1, 2)), true, LEVELS, p);
        assertThat(ok.enter()).as(ok.reason()).isTrue();
        // composite = 0.35·2/3 + 0.2·2/3 + 0.25·1/2 + 0.2·1 = 0.69167
        assertThat(ok.composite()).isEqualTo(0.692);
        assertThat(JevBotRules.stage2(result(stage2("long_continuation", 0.7, 0.6, 2, 2, 1, 2)), false, LEVELS, p).reason()).contains("not short_continuation");
        assertThat(JevBotRules.stage2(result(stage2("long_continuation", 0.5, 0.6, 2, 2, 1, 2)), true, LEVELS, p).reason()).startsWith("P(setup) 0.5");
        assertThat(JevBotRules.stage2(result(stage2("long_continuation", 0.7, 0.3, 2, 2, 1, 2)), true, LEVELS, p).reason()).startsWith("confidence 0.3");
        assertThat(JevBotRules.stage2(result(stage2("long_continuation", 0.7, 0.6, 1, 1, 1, 1)), true, LEVELS, p).reason()).startsWith("composite");
        // a single score under 0.25 with a good composite
        assertThat(JevBotRules.stage2(result(stage2("long_continuation", 0.7, 0.6, 3, 3, 0, 2)), true, LEVELS, p).reason()).startsWith("index_alignment 0.0");
        // candles-only: without the book questions the composite uses the remaining weights
        Map<String, Integer> candlesOnly = Map.of("trend_quality", 4, "index_alignment", 3, "liquidity", 3);
        assertThat(JevBotRules.stage2(result(stage2("long_continuation", 0.7, 0.6, 2, 0, 1, 2)), true, candlesOnly, p).enter()).isTrue();
    }

    static JevBotState.PositionFacts facts(double price, double stop, long minutes) {
        return new JevBotState.PositionFacts(true, 100, stop, 98, price, Math.max(price, 101.5), minutes);
    }

    static Map<String, JevAnswer> position(double thesis, double exitNow, double takeProfit) {
        return Map.of("thesis", score("thesis", thesis), "exit_now", noul("exit_now", exitNow), "take_profit", noul("take_profit", takeProfit));
    }

    @Test
    void positionRulesInOrder() throws Exception {
        JsonNode p = params("bot-position");
        assertThat(JevBotRules.position(result(position(0.6, 0.1, 0.1)), facts(100.5, 98, 5), p).action()).isEqualTo(JevBotRules.PositionAction.EXIT);
        assertThat(JevBotRules.position(result(position(2, 0.75, 0.1)), facts(100.5, 98, 5), p).action()).isEqualTo(JevBotRules.PositionAction.EXIT);
        assertThat(JevBotRules.position(result(position(2, 0.1, 0.8)), facts(100.5, 98, 5), p).action()).isEqualTo(JevBotRules.PositionAction.TAKE_PROFIT);
        // up 0.5R (101 on a 2-point risk) with a weakening thesis: stop to entry, once
        assertThat(JevBotRules.position(result(position(1.2, 0.1, 0.1)), facts(101, 98, 5), p).action())
                .isEqualTo(JevBotRules.PositionAction.MOVE_STOP_TO_ENTRY);
        assertThat(JevBotRules.position(result(position(1.2, 0.1, 0.1)), facts(101, 100, 5), p).action()).isEqualTo(JevBotRules.PositionAction.HOLD);
        // time stop: 30 minutes without profit
        assertThat(JevBotRules.position(result(position(2, 0.1, 0.1)), facts(99.9, 98, 30), p).reason()).startsWith("time stop");
        assertThat(JevBotRules.position(result(position(2, 0.1, 0.1)), facts(100.2, 98, 45), p).action()).isEqualTo(JevBotRules.PositionAction.HOLD);
    }

    static List<Candle> bars(int n, double start, double step, long volume) {
        List<Candle> out = new ArrayList<>();
        UUID id = UUID.randomUUID();
        Instant t = Instant.parse("2026-09-23T03:45:00Z");
        for (int i = 0; i < n; i++) {
            BigDecimal o = BigDecimal.valueOf(start + step * i).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal c = BigDecimal.valueOf(start + step * (i + 1)).setScale(2, java.math.RoundingMode.HALF_UP);
            out.add(new Candle(id, Timeframe.M1, t.plusSeconds(60L * i), o, o.max(c).add(new BigDecimal("0.05")), o.min(c).subtract(new BigDecimal("0.05")), c,
                    volume, 0, false));
        }
        return out;
    }

    @Test
    void theStateUsesNamedBucketsAndLeavesOutWhatIsMissing() {
        List<Candle> rising = bars(20, 100, 0.1, 1000);
        double[] baseline = new double[375];
        for (int m = 0; m < 375; m++) {
            baseline[m] = 500.0 * (m + 1); // half of today's pace
        }
        JevBotState.Features f = JevBotState.features(rising, baseline, List.of());
        var stock = JevBotState.stock(f);
        assertThat(stock.path("return_5m").asText()).isEqualTo("up");
        assertThat(stock.path("day_range_position").asText()).isEqualTo("top");
        assertThat(stock.path("vwap_position").asText()).isEqualTo("far_above");
        assertThat(stock.path("relative_volume").asText()).isEqualTo("heavy");
        assertThat(stock.path("volume_last_5m_vs_prior_10m").asText()).isEqualTo("steady");
        assertThat(stock.has("return_60m")).isFalse(); // 20 bars: no 60-minute return
        assertThat(stock.path("last_bars")).hasSize(10);
        assertThat(JevBotState.book(f)).isNull();     // no order-book data: no book block

        Candle last = rising.get(rising.size() - 1);
        List<BarMicro> micro = new ArrayList<>();
        for (int i = 15; i < 20; i++) {
            Candle c = rising.get(i);
            micro.add(new BarMicro(c.instrumentId(), Timeframe.M1, c.openTime(), 0.35, 0.3, 1.5, 0.7, 70, 30, 12, 12));
        }
        JevBotState.Features withBook = JevBotState.features(rising, null, micro);
        assertThat(withBook.imbalance()).isEqualTo(0.35);
        var book = JevBotState.book(withBook);
        assertThat(book.path("book_imbalance").asText()).isEqualTo("bid_heavy");
        assertThat(book.path("buy_sell_quantity").asText()).isEqualTo("buyers");
        assertThat(book.path("trade_flow_5m").asText()).isEqualTo("buying");
        assertThat(JevBotState.stock(withBook).has("relative_volume")).isFalse();
        assertThat(last.openTime()).isEqualTo(micro.get(4).openTime());
    }
}
