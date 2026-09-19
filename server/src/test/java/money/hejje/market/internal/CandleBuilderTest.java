package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.event.MarketTick;
import money.hejje.market.Candle;
import org.junit.jupiter.api.Test;

class CandleBuilderTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final UUID INSTR = UUID.fromString("0192a000-0000-7000-8000-0000000000aa");

    static List<MarketTick> fixtureTicks() throws Exception {
        List<MarketTick> ticks = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                CandleBuilderTest.class.getClassLoader().getResourceAsStream("market/ticks-fixture.csv"), StandardCharsets.UTF_8))) {
            in.readLine();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",");
                Instant ts = LocalDateTime.parse(c[0]).atZone(IST).toInstant();
                ticks.add(new MarketTick(INSTR, ts, new java.math.BigDecimal(c[1]), null, null, Long.parseLong(c[2]), 0, MarketTick.Mode.FULL));
            }
        }
        return ticks;
    }

    static List<Candle> buildAll(List<MarketTick> ticks) {
        CandleBuilder builder = new CandleBuilder();
        List<Candle> closed = new ArrayList<>();
        for (MarketTick tick : ticks) {
            closed.addAll(builder.onTick(tick));
        }
        // advance the clock past the last tick's minute so the final minute and its rollups close
        closed.addAll(builder.onClock(LocalDateTime.parse("2026-09-08T09:21:00").atZone(IST).toInstant()));
        return closed;
    }

    static Candle at(List<Candle> candles, Timeframe tf, String istMinute) {
        Instant t = LocalDateTime.parse(istMinute).atZone(IST).toInstant();
        return candles.stream().filter(c -> c.timeframe() == tf && c.openTime().equals(t)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + tf + " candle at " + istMinute));
    }

    @Test
    void buildsOneMinuteCandlesWithSyntheticFill() throws Exception {
        List<Candle> candles = buildAll(fixtureTicks());
        List<Candle> minute = candles.stream().filter(c -> c.timeframe() == Timeframe.M1).toList();
        // 09:15..09:20 = 6 one-minute candles (09:17 and 09:19 synthetic)
        assertThat(minute).extracting(c -> c.openTime().atZone(IST).toLocalTime().toString())
                .containsExactly("09:15", "09:16", "09:17", "09:18", "09:19", "09:20");

        Candle m15 = at(candles, Timeframe.M1, "2026-09-08T09:15:00");
        assertThat(m15.open()).isEqualByComparingTo("100.00");
        assertThat(m15.high()).isEqualByComparingTo("101.00");
        assertThat(m15.low()).isEqualByComparingTo("100.00");
        assertThat(m15.close()).isEqualByComparingTo("100.50");
        assertThat(m15.volume()).isEqualTo(40);
        assertThat(m15.synthetic()).isFalse();

        Candle m16 = at(candles, Timeframe.M1, "2026-09-08T09:16:00");
        assertThat(m16.close()).isEqualByComparingTo("103.00");
        assertThat(m16.volume()).isEqualTo(50);

        Candle synthetic = at(candles, Timeframe.M1, "2026-09-08T09:17:00");
        assertThat(synthetic.synthetic()).isTrue();
        assertThat(synthetic.open()).isEqualByComparingTo("103.00");
        assertThat(synthetic.close()).isEqualByComparingTo("103.00");
        assertThat(synthetic.high()).isEqualByComparingTo("103.00");
        assertThat(synthetic.low()).isEqualByComparingTo("103.00");
        assertThat(synthetic.volume()).isZero();

        Candle m18 = at(candles, Timeframe.M1, "2026-09-08T09:18:00");
        assertThat(m18.open()).isEqualByComparingTo("104.00");
        assertThat(m18.close()).isEqualByComparingTo("103.50");
        assertThat(m18.volume()).isEqualTo(30);

        assertThat(at(candles, Timeframe.M1, "2026-09-08T09:19:00").synthetic()).isTrue();
    }

    @Test
    void derivesFiveMinuteCandleOnIstBoundaries() throws Exception {
        List<Candle> candles = buildAll(fixtureTicks());
        Candle five = at(candles, Timeframe.M5, "2026-09-08T09:15:00");
        assertThat(five.open()).isEqualByComparingTo("100.00");
        assertThat(five.high()).isEqualByComparingTo("104.00");
        assertThat(five.low()).isEqualByComparingTo("100.00");
        assertThat(five.close()).isEqualByComparingTo("103.50");
        assertThat(five.volume()).isEqualTo(120); // 40 + 50 + 0 + 30 + 0
        assertThat(five.synthetic()).isFalse();

        // every 5m candle opens on a :00/:05/:10... boundary
        assertThat(candles.stream().filter(c -> c.timeframe() == Timeframe.M5))
                .allSatisfy(c -> assertThat(c.openTime().atZone(IST).getMinute() % 5).isZero());
    }

    @Test
    void floorAlignsToIstBoundaries() {
        Instant t = LocalDateTime.parse("2026-09-08T09:17:34").atZone(IST).toInstant();
        assertThat(CandleBuilder.floorTo(t, Timeframe.M1).atZone(IST).toLocalTime().toString()).isEqualTo("09:17");
        assertThat(CandleBuilder.floorTo(t, Timeframe.M5).atZone(IST).toLocalTime().toString()).isEqualTo("09:15");
        assertThat(CandleBuilder.floorTo(t, Timeframe.M15).atZone(IST).toLocalTime().toString()).isEqualTo("09:15");
        assertThat(CandleBuilder.floorTo(t, Timeframe.H1).atZone(IST).toLocalTime().toString()).isEqualTo("09:00");
    }

    @Test
    void theFirstMinuteOfANewDayCountsItsOwnVolume() {
        CandleBuilder builder = new CandleBuilder();
        List<Candle> closed = new ArrayList<>();
        java.util.function.BiFunction<String, Long, MarketTick> tick = (ist, cumulative) -> new MarketTick(INSTR, LocalDateTime.parse(ist).atZone(IST).toInstant(),
                new java.math.BigDecimal("100.00"), null, null, cumulative, 0, MarketTick.Mode.FULL);
        closed.addAll(builder.onTick(tick.apply("2026-09-08T15:29:10", 900_000L)));
        closed.addAll(builder.onTick(tick.apply("2026-09-08T15:29:50", 950_000L)));
        // next morning the exchange's cumulative day volume starts again from zero
        closed.addAll(builder.onTick(tick.apply("2026-09-09T09:15:05", 1_200L)));
        closed.addAll(builder.onTick(tick.apply("2026-09-09T09:15:40", 3_000L)));
        closed.addAll(builder.onClock(LocalDateTime.parse("2026-09-09T09:16:00").atZone(IST).toInstant()));
        assertThat(at(closed, Timeframe.M1, "2026-09-09T09:15:00").volume()).as("not max(0, 3000 − 950000)").isEqualTo(3_000);
    }
}
