package money.hejje.scoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The PRD section 14 breakdown table: base score with its components, bounded adjustments, final score.
 *
 * @param base        0-100 base backtest score (after the small-sample cap)
 * @param cap         why the base was capped, or null
 * @param finalScore  base + adjustments, clipped to 0-100
 */
public record ScoreBreakdown(UUID id, UUID versionId, UUID instrumentId, Instant computedAt, UUID baseBacktestId, double base, String cap,
        List<Component> components, List<Adjustment> adjustments, int finalScore) {

    /**
     * One base component.
     *
     * @param score        0-100 mapped value
     * @param contribution weight × score
     * @param evidence     the observed inputs, for example {@code {outOfSample: 0.31, validation: 0.22}}
     */
    public record Component(String name, double weight, double score, double contribution, Map<String, Object> evidence) {
        public Component {
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }
    }

    public ScoreBreakdown {
        components = List.copyOf(components);
        adjustments = List.copyOf(adjustments);
    }
}
