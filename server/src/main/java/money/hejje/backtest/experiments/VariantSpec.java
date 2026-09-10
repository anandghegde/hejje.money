package money.hejje.backtest.experiments;

import java.util.Map;

/** A variant to test: a name, why, and the delta applied to the base definition (see {@link VariantDelta}). */
public record VariantSpec(String name, String description, Map<String, Object> delta) {
}
