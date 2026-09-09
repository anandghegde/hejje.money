package money.hejje.regime;

import java.time.LocalDate;

/**
 * Outcome of a historical labelling run.
 *
 * @param sessions expected trading sessions in the range
 * @param labelled sessions that had index data and were written
 * @param hash     SHA-256 over the written labels; identical inputs and rules give an identical hash
 */
public record RegimeLabelResult(LocalDate from, LocalDate to, String classifierVersion, int sessions, int labelled, String hash) {
}
