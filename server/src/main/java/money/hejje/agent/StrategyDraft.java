package money.hejje.agent;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of the natural-language strategy builder (plan M4.6): when {@code created}, a DRAFT version (change note
 * "NL draft") with its YAML and the rules in words; otherwise the last attempt and its validation errors. {@code parentYaml}
 * is the previous version when the draft is a new version of an existing strategy. {@code attempts} lists every YAML the
 * model produced with its errors.
 */
public record StrategyDraft(boolean created, UUID strategyId, String slug, UUID versionId, Integer version, String status, String changeNote, String yaml,
        List<String> rules, String parentYaml, Integer parentVersion, List<Attempt> attempts, List<String> errors) {

    public record Attempt(int iteration, String yaml, List<String> errors) {}
}
