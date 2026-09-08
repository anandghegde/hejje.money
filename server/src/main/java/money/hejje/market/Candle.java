package money.hejje.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import money.hejje.common.Timeframe;

/**
 * One OHLCV(+OI) candle (PRD section 45). {@code openTime} is the candle's start, aligned to the timeframe on IST
 * boundaries. A {@code synthetic} candle covers a minute with no ticks: {@code open=high=low=close} the previous close
 * and {@code volume=0}.
 */
public record Candle(UUID instrumentId, Timeframe timeframe, Instant openTime, BigDecimal open, BigDecimal high, BigDecimal low,
        BigDecimal close, long volume, long oi, boolean synthetic) {

    public Candle {
        if (instrumentId == null || timeframe == null || openTime == null || open == null || high == null || low == null || close == null) {
            throw new IllegalArgumentException("Candle needs instrument, timeframe, openTime and OHLC");
        }
    }
}
