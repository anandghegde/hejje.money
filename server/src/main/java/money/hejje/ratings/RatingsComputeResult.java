package money.hejje.ratings;

import java.time.LocalDate;

/**
 * Outcome of a ratings run.
 *
 * @param sessions sessions in the range
 * @param computed sessions written by this run (the others already had rows under the engine version)
 * @param rows     rating rows of all sessions in the range
 * @param hash     SHA-256 over those rows; identical candles and engine version give an identical hash
 */
public record RatingsComputeResult(LocalDate from, LocalDate to, String engineVersion, int sessions, int computed, int rows, String hash) {
}
