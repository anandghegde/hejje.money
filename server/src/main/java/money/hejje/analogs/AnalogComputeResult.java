package money.hejje.analogs;

import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of an analog run.
 *
 * @param summaries summaries written (dates x symbols x lookbacks that had none under the engine version)
 * @param hash      SHA-256 over every summary of the dates, stored or new
 */
public record AnalogComputeResult(List<LocalDate> dates, String engineVersion, int symbols, int summaries, long millis, String hash) {
}
