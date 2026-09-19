package money.hejje.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.InstrumentType;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.costs.CostModel;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.internal.DefinitionParser;
import org.junit.jupiter.api.Test;

/**
 * The Phase 6 bundled strategies (plan M6.3) on hand-built sessions: each produces its signal on the bar worked out by
 * hand in the comments, and nothing when its distinguishing filter fails. Bar-close fills, so a trade's entry time is
 * the close of its signal bar. The files are read from strategies/ with the universe replaced by one test instrument.
 */
class NewStrategiesSignalTest {

    static final ZoneId IST = SyntheticSessions.IST;
    static final UUID INSTRUMENT = UUID.fromString("00000000-0000-7000-8000-0000000006e1");
    static final InstrumentMeta META = new InstrumentMeta(INSTRUMENT, "NSE:TEST", InstrumentType.EQ, 1, new BigDecimal("0.05"));
    /** Seven warm-up sessions (NR7 needs seven completed sessions) and the trading day. */
    static final List<LocalDate> WARMUP = List.of(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3),
            LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 8));
    static final LocalDate DAY = LocalDate.of(2026, 9, 9);
    static final long WARM_VOLUME = 10_000;

    final HejjeClock clock = new HejjeClock(Clock.fixed(LocalDateTime.of(2026, 9, 20, 10, 0).atZone(IST).toInstant(), IST), IST, (d, e) -> false);
    final BacktestEngine engine = new BacktestEngine(new CostModel(BacktestEngineTest.costProperties()), clock);

    /** open, high, low, close and volume of one 5-minute bar. */
    record B(double open, double high, double low, double close, long volume) {}

    static B b(double open, double high, double low, double close) {
        return new B(open, high, low, close, WARM_VOLUME);
    }

    static B b(double open, double high, double low, double close, long volume) {
        return new B(open, high, low, close, volume);
    }

    /** A full session: the given bars from 09:15, then flat bars at the last close until 15:25. */
    static List<Candle> session(LocalDate date, List<B> leading) {
        return session(date, leading, 0);
    }

    /** As {@link #session(LocalDate, List)} with filler bars spanning the last close ± {@code halfRange}. */
    static List<Candle> session(LocalDate date, List<B> leading, double halfRange) {
        List<Candle> out = new ArrayList<>();
        B last = leading.get(leading.size() - 1);
        for (int i = 0; i < SyntheticSessions.BARS; i++) {
            B x = i < leading.size() ? leading.get(i) : b(last.close(), last.close() + halfRange, last.close() - halfRange, last.close());
            LocalTime open = LocalTime.of(9, 15).plusMinutes(5L * i);
            out.add(new Candle(INSTRUMENT, Timeframe.M5, date.atTime(open).atZone(IST).toInstant(), price(x.open()), price(x.high()), price(x.low()),
                    price(x.close()), x.volume(), 0, false));
        }
        return out;
    }

    static BigDecimal price(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Warm-up sessions flat at 100 with the given half-range per session (default 0.5: range 1.0, close 100). */
    static List<Candle> warmUp(double... halfRanges) {
        List<Candle> out = new ArrayList<>();
        for (int d = 0; d < WARMUP.size(); d++) {
            double h = d < halfRanges.length ? halfRanges[d] : 0.5;
            out.addAll(session(WARMUP.get(d), List.of(b(100, 100 + h, 100 - h, 100))));
        }
        return out;
    }

    static StrategyDefinition load(String slug) throws Exception {
        String text = Files.readString(Path.of("../strategies/" + slug + ".yaml")).replaceAll("universe:\n(  - .*\n)+", "universe: [NSE:TEST]\n");
        return new DefinitionParser().parse(text);
    }

    List<BacktestTrade> trades(String slug, List<Candle> warm, List<B> day) throws Exception {
        return trades(slug, warm, day, 0);
    }

    List<BacktestTrade> trades(String slug, List<Candle> warm, List<B> day, double fillerHalfRange) throws Exception {
        List<Candle> candles = new ArrayList<>(warm);
        candles.addAll(session(DAY, day, fillerHalfRange));
        BacktestSpec spec = new BacktestSpec(UUID.randomUUID(), List.of(INSTRUMENT), Timeframe.M5, DAY, DAY, FillModel.BAR_CLOSE, 0, null, Splits.NONE,
                Money.ofRupees(10_000_000), null);
        return engine.run(new BacktestInput(load(slug), spec, Map.of(INSTRUMENT, META), Map.of(INSTRUMENT, candles), Money.ofRupees(2000))).trades();
    }

    static java.time.Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(IST).toInstant();
    }

    static void assertOneEntry(List<BacktestTrade> trades, Side side, int hour, int minute, String close) {
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).side()).isEqualTo(side);
        assertThat(trades.get(0).entryTime()).isEqualTo(at(hour, minute));
        assertThat(trades.get(0).entryPrice()).isEqualByComparingTo(close);
    }

    @Test
    void cprBreakout() throws Exception {
        // previous session H 100.5, L 99.5, C 100: P = BC = TC = 100, width 0 % < 0.2. The 09:15-09:25 bars stay under 100;
        // the 09:30 bar closes 100.30 (previous close 99.80 <= 100) above VWAP (~99.8): the signal bar closes at 09:35.
        List<B> day = List.of(b(99.8, 99.9, 99.6, 99.7), b(99.7, 99.8, 99.5, 99.6), b(99.6, 99.9, 99.5, 99.8), b(99.8, 100.4, 99.8, 100.3));
        assertOneEntry(trades("cpr_breakout", warmUp(), day), Side.BUY, 9, 35, "100.30");
        // a wide CPR blocks it: previous H 101, L 99, C 99 → P 99.667, BC 100, TC 99.333, so the top is still 100 but
        // the width is 0.667 / 99.667 = 0.67 %
        List<Candle> wide = new ArrayList<>(warmUp());
        wide.subList(wide.size() - SyntheticSessions.BARS, wide.size()).clear();
        wide.addAll(session(WARMUP.get(WARMUP.size() - 1), List.of(b(101, 101, 99, 101), b(101, 101, 99, 99))));
        assertThat(trades("cpr_breakout", wide, day)).isEmpty();
    }

    @Test
    void openLowLong() throws Exception {
        // open 100.50 is still the low at the 09:30 close; close 101.40 > VWAP (~101.1) and > previous close 100
        List<B> day = List.of(b(100.5, 101, 100.5, 100.9), b(100.9, 101.3, 100.7, 101.2), b(101.2, 101.5, 101.0, 101.4));
        assertOneEntry(trades("open_low_long", warmUp(), day), Side.BUY, 9, 30, "101.40");
        // the 09:20 bar trades below the open: no signal
        List<B> broken = List.of(b(100.5, 101, 100.5, 100.9), b(100.9, 101.3, 100.4, 101.2), b(101.2, 101.5, 101.0, 101.4));
        assertThat(trades("open_low_long", warmUp(), broken)).isEmpty();
    }

    @Test
    void openHighShort() throws Exception {
        // open 99.50 is still the high at the 09:30 close; close 98.60 < VWAP (~98.9) and < previous close 100
        List<B> day = List.of(b(99.5, 99.5, 99.0, 99.1), b(99.1, 99.3, 98.8, 98.9), b(98.9, 99.0, 98.5, 98.6));
        assertOneEntry(trades("open_high_short", warmUp(), day), Side.SELL, 9, 30, "98.60");
        List<B> broken = List.of(b(99.5, 99.5, 99.0, 99.1), b(99.1, 99.6, 98.8, 98.9), b(98.9, 99.0, 98.5, 98.6));
        assertThat(trades("open_high_short", warmUp(), broken)).isEmpty();
    }

    @Test
    void niftyNr7Orb() throws Exception {
        // opening range 100..102; the 09:30 bar closes 103 above the range and VWAP on twice the slot's usual volume
        List<B> day = List.of(b(101, 102, 100, 101), b(101, 101.5, 100.5, 101), b(101, 101.8, 100.2, 101.5), b(101.5, 103.2, 101.4, 103, 2 * WARM_VOLUME));
        // the last warm-up session (range 0.6) is the narrowest of seven: NR7
        assertOneEntry(trades("nifty_nr7_orb", warmUp(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.3), day), Side.BUY, 9, 35, "103.00");
        // an earlier session (range 0.4) was narrower than the last one (0.6): not NR7
        assertThat(trades("nifty_nr7_orb", warmUp(0.5, 0.5, 0.2, 0.5, 0.5, 0.5, 0.3), day)).isEmpty();
    }

    @Test
    void stockInPlayOrb() throws Exception {
        // gap +1.5 % (open 101.50 vs close 100); 5-minute range high 102; the 09:20 bar closes 102.50 above it and VWAP
        // on 2.5× the slot's usual volume: the signal bar closes at 09:25
        List<B> day = List.of(b(101.5, 102, 101.3, 101.8, 30_000), b(101.8, 102.6, 101.7, 102.5, 25_000));
        List<BacktestTrade> trades = trades("stock_in_play_orb", warmUp(), day);
        assertOneEntry(trades, Side.BUY, 9, 25, "102.50");
        assertThat(trades.get(0).target()).isNull(); // held to the force exit
        assertThat(trades.get(0).exitReason()).isEqualTo(ExitReason.FORCE_EXIT);
        // a gap under 1 % is not a stock in play
        List<B> smallGap = List.of(b(100.8, 102, 100.7, 101.8, 30_000), b(101.8, 102.6, 101.7, 102.5, 25_000));
        assertThat(trades("stock_in_play_orb", warmUp(), smallGap)).isEmpty();
    }

    @Test
    void niftyIntradayMomentum() throws Exception {
        // previous close 100; the 09:40 bar closes (09:45) at 100.80: +0.8 % in the first half hour. Long enters on the
        // bar closing at 14:45 (filler bars ± 0.2 keep the ATR stop away) and is flat at the force exit
        List<B> up = List.of(b(100, 100.3, 99.9, 100.2), b(100.2, 100.4, 100.1, 100.3), b(100.3, 100.5, 100.2, 100.4), b(100.4, 100.6, 100.3, 100.5),
                b(100.5, 100.7, 100.4, 100.6), b(100.6, 100.9, 100.5, 100.8));
        List<BacktestTrade> longs = trades("nifty_intraday_momentum_long", warmUp(), up, 0.2);
        assertOneEntry(longs, Side.BUY, 14, 45, "100.80");
        assertThat(longs.get(0).exitReason()).isEqualTo(ExitReason.FORCE_EXIT);
        assertThat(trades("nifty_intraday_momentum_short", warmUp(), up, 0.2)).isEmpty();
        // mirror: −0.8 % → the short enters at 14:45
        List<B> down = List.of(b(100, 100.1, 99.7, 99.8), b(99.8, 99.9, 99.6, 99.7), b(99.7, 99.8, 99.5, 99.6), b(99.6, 99.7, 99.4, 99.5),
                b(99.5, 99.6, 99.3, 99.4), b(99.4, 99.5, 99.1, 99.2));
        assertOneEntry(trades("nifty_intraday_momentum_short", warmUp(), down, 0.2), Side.SELL, 14, 45, "99.20");
        assertThat(trades("nifty_intraday_momentum_long", warmUp(), down, 0.2)).isEmpty();
    }

    @Test
    void supertrendVwap() throws Exception {
        // after seven quiet sessions ATR(10) is small: Supertrend(10, 3) flips down on the 09:15 bar (line 99.20) and stays
        // down through 09:30 (96.818, close 96.80 just under it); the 09:35 bar closes 98.80 above it (previous close 96.80
        // <= 96.818) and above VWAP (~96.54): signal bar closes at 09:40. The 10:55 bar closes 101.20 under the line
        // (102.86): the exit rule closes the trade at 11:00, above the 1.5 × ATR(14) stop (98.80 − 1.5 × 0.656 ≈ 97.82).
        List<B> day = new ArrayList<>(List.of(b(100, 100, 96, 96.5), b(96.5, 96.8, 95, 95.2), b(95.2, 95.5, 94.8, 95), b(95, 97, 95, 96.8),
                b(96.8, 99, 96.7, 98.8), b(98.8, 101, 98.7, 100.9), b(100.9, 103, 100.8, 102.8)));
        for (int i = 0; i < 13; i++) {
            day.add(b(102.8, 102.8, 102.8, 102.8));
        }
        day.add(b(102.8, 102.8, 101, 101.2));
        List<BacktestTrade> trades = trades("supertrend_vwap", warmUp(), day);
        assertOneEntry(trades, Side.BUY, 9, 40, "98.80");
        assertThat(trades.get(0).exitReason()).isEqualTo(ExitReason.RULE_EXIT);
        assertThat(trades.get(0).exitTime()).isEqualTo(at(11, 0));
    }
}
