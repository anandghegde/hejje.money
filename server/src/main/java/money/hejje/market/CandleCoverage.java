package money.hejje.market;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.Timeframe;

/** What the historical store holds for one instrument and timeframe. */
public record CandleCoverage(UUID instrumentId, Timeframe timeframe, Instant from, Instant to, long candleCount) {

    public boolean isEmpty() {
        return candleCount == 0;
    }
}
