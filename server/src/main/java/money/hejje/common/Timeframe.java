package money.hejje.common;

import java.time.Duration;

/** Candle timeframes Hejje builds and stores. */
public enum Timeframe {
    M1(Duration.ofMinutes(1)),
    M3(Duration.ofMinutes(3)),
    M5(Duration.ofMinutes(5)),
    M15(Duration.ofMinutes(15)),
    H1(Duration.ofHours(1)),
    D1(Duration.ofDays(1));

    private final Duration duration;

    Timeframe(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    public boolean isIntraday() {
        return this != D1;
    }
}
