package money.hejje.strategy.dsl;

import java.math.BigDecimal;

/** Canonical number rendering for the DSL: integers without a fraction, otherwise the shortest plain decimal. */
final class Numbers {

    private Numbers() {
    }

    static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
