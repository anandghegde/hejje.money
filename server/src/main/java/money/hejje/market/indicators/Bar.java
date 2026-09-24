package money.hejje.market.indicators;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import money.hejje.market.BarMicro;
import money.hejje.market.Candle;

/**
 * A closed candle in indicator-friendly form: doubles plus the IST session date and the bar's open/close times of
 * day. Indicator arithmetic is analytics, not money (README rule 4 applies to prices on orders, which stay decimal).
 */
public record Bar(Instant openTime, Instant closeTime, LocalDate session, LocalTime openTimeOfDay, LocalTime closeTimeOfDay,
        double open, double high, double low, double close, double volume, boolean synthetic, BarMicro micro) {

    /** A bar without order-book data (every bar replayed from candle history). */
    public Bar(Instant openTime, Instant closeTime, LocalDate session, LocalTime openTimeOfDay, LocalTime closeTimeOfDay, double open, double high,
            double low, double close, double volume, boolean synthetic) {
        this(openTime, closeTime, session, openTimeOfDay, closeTimeOfDay, open, high, low, close, volume, synthetic, null);
    }

    public static Bar of(Candle candle, ZoneId zone) {
        return of(candle, null, zone);
    }

    /** {@code micro}: the bar's order-book and flow data (plan M9.4), null when its ticks carried none. */
    public static Bar of(Candle candle, BarMicro micro, ZoneId zone) {
        Instant close = candle.openTime().plus(candle.timeframe().duration());
        return new Bar(candle.openTime(), close, candle.openTime().atZone(zone).toLocalDate(),
                candle.openTime().atZone(zone).toLocalTime(), close.atZone(zone).toLocalTime(),
                candle.open().doubleValue(), candle.high().doubleValue(), candle.low().doubleValue(), candle.close().doubleValue(),
                candle.volume(), candle.synthetic(), micro);
    }

    public double typicalPrice() {
        return (high + low + close) / 3.0;
    }
}
