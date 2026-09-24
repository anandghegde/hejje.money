package money.hejje.ratings;

import java.time.LocalDate;

/**
 * Outcome of a bases run.
 *
 * @param detected    bases detected by this run
 * @param transitions status changes written by this run (detections not counted)
 * @param hash        SHA-256 over every base detected on or before {@code to} with its status as of {@code to}
 */
public record BasesComputeResult(LocalDate from, LocalDate to, String engineVersion, int instruments, int detected, int transitions, String hash) {
}
