package money.hejje.backtest.experiments;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One tested variant. The baseline (ordinal 0, empty delta) is the base version itself. */
public record Variant(UUID id, UUID experimentId, int ordinal, String name, String description, Map<String, Object> delta, String definitionYaml,
        VariantStatus status, VariantMetrics metrics, Integer rank, Double score, String verdict, List<String> warnings, int parameterCount, int conditionCount,
        String error, UUID promotedVersionId) {

    public Variant {
        delta = delta == null ? Map.of() : delta;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean baseline() {
        return ordinal == 0;
    }
}
