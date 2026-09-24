package money.hejje.ratings;

import java.time.LocalDate;

/** The universe's D1 candles are current up to and including {@code date}'s close; the nightly computations chain off it. */
public record DailyCandlesRefreshed(LocalDate date) {
}
