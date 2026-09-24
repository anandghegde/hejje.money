package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.event.MarketTick;
import money.hejje.market.BarMicro;
import money.hejje.market.Candle;
import org.junit.jupiter.api.Test;

/** Hand-computed order-book and flow features per bar, aggregation to M5, and determinism (plan M9.4). */
class BarMicroTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000a004");

    static MarketTick t(String ist, String price, long volume, Long bid5, Long ask5, Long buy, Long sell) {
        return new MarketTick(ID, LocalDateTime.parse(ist).atZone(IST).toInstant(), new BigDecimal(price), null, null, volume, 0, MarketTick.Mode.FULL,
                bid5, ask5, buy, sell);
    }

    /** Minute 09:20: four book ticks; minute 09:21: two; minute 09:22: LTP-style ticks only. */
    static List<MarketTick> ticks() {
        return List.of(
                t("2026-09-23T09:20:05", "100.00", 1000, 600L, 400L, 9000L, 6000L),  // imb +0.2; first tick: no flow
                t("2026-09-23T09:20:20", "100.10", 1300, 500L, 500L, 9100L, 6000L),  // imb 0; +300 up
                t("2026-09-23T09:20:40", "100.10", 1400, 300L, 700L, 9200L, 6100L),  // imb −0.4; unchanged price: ignored
                t("2026-09-23T09:20:55", "100.00", 1600, 800L, 200L, 9000L, 6000L),  // imb +0.6; +200 down; ratio 1.5
                t("2026-09-23T09:21:10", "100.20", 2000, 700L, 300L, 8000L, 8000L),  // imb +0.4; +400 up
                t("2026-09-23T09:21:50", "100.30", 2100, 450L, 550L, 8000L, 10000L), // imb −0.1; +100 up; ratio 0.8
                new MarketTick(ID, LocalDateTime.parse("2026-09-23T09:22:30").atZone(IST).toInstant(), new BigDecimal("100.40"), null, null, 2500, 0,
                        MarketTick.Mode.LTP));
    }

    record Run(List<Candle> candles, List<BarMicro> micro) {}

    static Run run(List<MarketTick> ticks) {
        CandleBuilder candles = new CandleBuilder();
        BarMicroBuilder micro = new BarMicroBuilder();
        List<Candle> closed = new ArrayList<>();
        List<BarMicro> out = new ArrayList<>();
        for (MarketTick tick : ticks) {
            List<Candle> c = candles.onTick(tick);
            micro.onTick(tick);
            closed.addAll(c);
        }
        closed.addAll(candles.onClock(LocalDateTime.parse("2026-09-23T09:25:00").atZone(IST).toInstant()));
        for (Candle c : closed) {
            BarMicro m = micro.onCandleClosed(c);
            if (m != null) {
                out.add(m);
            }
        }
        return new Run(closed, out);
    }

    static BarMicro find(List<BarMicro> all, Timeframe tf, String ist) {
        Instant at = LocalDateTime.parse(ist).atZone(IST).toInstant();
        return all.stream().filter(m -> m.timeframe() == tf && m.openTime().equals(at)).findFirst().orElse(null);
    }

    @Test
    void handComputedValuesPerMinute() {
        List<BarMicro> micro = run(ticks()).micro();
        BarMicro m20 = find(micro, Timeframe.M1, "2026-09-23T09:20:00");
        assertThat(m20.ticks()).isEqualTo(4);
        assertThat(m20.depthTicks()).isEqualTo(4);
        assertThat(m20.imbalanceClose()).isCloseTo(0.6, within(1e-12));
        assertThat(m20.imbalanceMean()).isCloseTo((0.2 + 0 - 0.4 + 0.6) / 4, within(1e-12));
        assertThat(m20.buySellRatio()).isCloseTo(1.5, within(1e-12));
        assertThat(m20.upVolume()).isEqualTo(300);
        assertThat(m20.downVolume()).isEqualTo(200);
        assertThat(m20.upVolumeShare()).isCloseTo(0.6, within(1e-12));

        BarMicro m21 = find(micro, Timeframe.M1, "2026-09-23T09:21:00");
        assertThat(m21.imbalanceClose()).isCloseTo(-0.1, within(1e-12));
        assertThat(m21.imbalanceMean()).isCloseTo(0.15, within(1e-12));
        assertThat(m21.buySellRatio()).isCloseTo(0.8, within(1e-12));
        assertThat(m21.upVolumeShare()).isEqualTo(1.0);

        // a minute whose ticks carried no book has no micro data; nor do synthetic minutes
        assertThat(find(micro, Timeframe.M1, "2026-09-23T09:22:00")).isNull();
        assertThat(find(micro, Timeframe.M1, "2026-09-23T09:23:00")).isNull();
    }

    @Test
    void derivedBarsAggregateTheirMinutes() {
        BarMicro m5 = find(run(ticks()).micro(), Timeframe.M5, "2026-09-23T09:20:00");
        assertThat(m5.ticks()).isEqualTo(6);
        assertThat(m5.depthTicks()).isEqualTo(6);
        assertThat(m5.imbalanceClose()).isCloseTo(-0.1, within(1e-12));                     // the last minute's
        assertThat(m5.imbalanceMean()).isCloseTo((0.2 + 0 - 0.4 + 0.6 + 0.4 - 0.1) / 6, within(1e-12)); // depth-tick weighted
        assertThat(m5.buySellRatio()).isCloseTo(0.8, within(1e-12));
        assertThat(m5.upVolumeShare()).isCloseTo(800.0 / 1000, within(1e-12));
    }

    @Test
    void theSameTicksGiveTheSameValues() {
        assertThat(run(ticks()).micro()).isEqualTo(run(ticks()).micro());
    }

    @Test
    void ticksWithoutBookGiveNoMicroAtAll() {
        List<MarketTick> plain = ticks().stream()
                .map(x -> new MarketTick(x.instrumentId(), x.ts(), x.lastPrice(), null, null, x.volume(), 0, MarketTick.Mode.QUOTE)).toList();
        assertThat(run(plain).micro()).isEmpty();
    }
}
