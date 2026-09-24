package money.hejje.analogs;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One historical window that looked like the benchmark. Matches are served unordered: the client sorts.
 *
 * @param endDate     last session of the matched window (its forward windows start the session after)
 * @param similarity  weighted, scaled distance; lower is closer
 * @param quality     1-5, the mean of the six component scores
 * @param components  raw distances: {@code path_correlation, shape_distance, volatility_distance, trend_distance,
 *                    range_position_distance, volume_distance, risk_distance}
 * @param scores      1-5 per component: {@code shape, trend, volatility, rangePosition, volume, risk}
 * @param returns     forward return in percent per forward window
 * @param path        the matched window's close path as percent from its first close (for a sparkline)
 */
public record AnalogMatch(UUID instrumentId, String symbol, LocalDate endDate, double similarity, double quality, Map<String, Double> components,
        Map<String, Double> scores, Map<String, Double> returns, List<Double> path) {
}
