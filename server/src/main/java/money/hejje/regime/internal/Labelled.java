package money.hejje.regime.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One dimension's outcome: the label, the numbers behind it and the sentence explaining it. */
record Labelled<L>(L label, Map<String, Object> features, List<String> evidence) {

    static <L> Labelled<L> of(L label, String evidence, Object... featurePairs) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (int i = 0; i + 1 < featurePairs.length; i += 2) {
            Object value = featurePairs[i + 1];
            if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
                continue;
            }
            if (value != null) {
                f.put(String.valueOf(featurePairs[i]), value);
            }
        }
        return new Labelled<>(label, f, List.of(evidence));
    }
}
