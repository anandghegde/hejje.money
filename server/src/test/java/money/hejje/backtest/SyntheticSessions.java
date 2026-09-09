package money.hejje.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.market.Candle;

/** Builds deterministic 5-minute sessions (09:15 .. 15:25) for engine tests. */
public final class SyntheticSessions {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final int BARS = 75;

    private SyntheticSessions() {
    }

    /** One bar spec: open, high, low, close (strings keep the test readable). */
    public record Ohlc(String open, String high, String low, String close) {}

    public static Ohlc bar(String open, String high, String low, String close) {
        return new Ohlc(open, high, low, close);
    }

    /** A flat session at {@code price} with tiny ranges: never triggers a breakout. */
    public static List<Candle> flat(UUID instrument, LocalDate date, String price) {
        List<Ohlc> bars = new ArrayList<>();
        BigDecimal p = new BigDecimal(price);
        for (int i = 0; i < BARS; i++) {
            bars.add(bar(p.toPlainString(), p.add(new BigDecimal("0.50")).toPlainString(), p.subtract(new BigDecimal("0.50")).toPlainString(), p.toPlainString()));
        }
        return session(instrument, date, bars, 10_000);
    }

    /**
     * A session whose first bars are given explicitly and whose remaining bars are flat at the last close.
     */
    public static List<Candle> session(UUID instrument, LocalDate date, List<Ohlc> leading, long volume) {
        List<Candle> out = new ArrayList<>();
        String lastClose = leading.isEmpty() ? "100" : leading.get(leading.size() - 1).close();
        for (int i = 0; i < BARS; i++) {
            Ohlc b = i < leading.size() ? leading.get(i) : bar(lastClose, lastClose, lastClose, lastClose);
            LocalTime open = LocalTime.of(9, 15).plusMinutes(5L * i);
            out.add(new Candle(instrument, Timeframe.M5, date.atTime(open).atZone(IST).toInstant(), new BigDecimal(b.open()), new BigDecimal(b.high()),
                    new BigDecimal(b.low()), new BigDecimal(b.close()), volume, 0, false));
        }
        return out;
    }

    /** Exactly {@code count} bars of a session, so data can end mid-day. */
    public static List<Candle> truncated(List<Candle> session, int count) {
        return new ArrayList<>(session.subList(0, count));
    }
}
