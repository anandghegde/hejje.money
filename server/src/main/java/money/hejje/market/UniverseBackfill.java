package money.hejje.market;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;

/**
 * Progress of a universe backfill: one parent job over a per-instrument child job each (plan M8.1).
 *
 * @param status     RUNNING, DONE (every child done) or PARTIAL (finished with failed children)
 * @param unresolved universe symbols the instrument master lacks (nothing was requested for them)
 * @param failed     {@code SYMBOL: error} per failed child
 */
public record UniverseBackfill(UUID jobId, String universe, Timeframe timeframe, Instant from, Instant to, String status,
        int childrenTotal, int childrenDone, int childrenFailed, long candlesWritten, List<String> unresolved, List<String> failed) {
}
