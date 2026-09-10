package money.hejje.backtest.experiments;

import java.util.List;

/** The definition a delta produces, validated but not run. */
public record VariantPreview(boolean valid, List<String> errors, String yaml, List<String> entryConditions, int parameterCount, int conditionCount) {
}
