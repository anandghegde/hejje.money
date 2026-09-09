package money.hejje.scoring;

import java.util.List;
import java.util.Map;

/**
 * PRD section 11: two versions of one strategy side by side with a templated verdict.
 *
 * @param deltas   per metric: {@code {metric, a, b, changePct}} (changePct null when not computable)
 * @param verdict  sentence built from the largest improvement and the largest regression
 */
public record VersionComparison(ComparisonRow a, ComparisonRow b, List<Map<String, Object>> deltas, String verdict) {
}
