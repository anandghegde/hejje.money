package money.hejje.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exchange price with exactly two decimals. Positive. Never a floating point number.
 *
 * @param value price in rupees, scale 2
 */
public record Price(BigDecimal value) implements Comparable<Price> {

    public Price {
        if (value == null) {
            throw new IllegalArgumentException("Price is required");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Price has more than two decimals: " + value.toPlainString());
        }
        value = value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Parses a price such as {@code "24930.05"}. */
    @JsonCreator
    public static Price of(String value) {
        return new Price(new BigDecimal(value));
    }

    /** JSON form is the plain decimal string, avoiding floating-point round-trips. */
    @JsonValue
    public String asText() {
        return value.toPlainString();
    }

    public static Price of(BigDecimal value) {
        return new Price(value);
    }

    /** True when this price is a whole multiple of the instrument tick size (for example {@code 0.05}). */
    public boolean isAlignedTo(BigDecimal tickSize) {
        if (tickSize.signum() <= 0) {
            throw new IllegalArgumentException("Tick size must be positive: " + tickSize.toPlainString());
        }
        return value.remainder(tickSize).compareTo(BigDecimal.ZERO) == 0;
    }

    /** Returns this price if it is aligned to the tick size, otherwise throws. */
    public Price alignedTo(BigDecimal tickSize) {
        if (!isAlignedTo(tickSize)) {
            throw new IllegalArgumentException(
                    "Price " + value.toPlainString() + " is not aligned to tick size " + tickSize.toPlainString());
        }
        return this;
    }

    /** Rounds to the nearest tick using the given rounding mode. */
    public Price roundedTo(BigDecimal tickSize, RoundingMode rounding) {
        BigDecimal ticks = value.divide(tickSize, 0, rounding);
        return new Price(ticks.multiply(tickSize));
    }

    /** Notional value of {@code quantity} units at this price. */
    public Money times(Quantity quantity) {
        return Money.of(value.multiply(BigDecimal.valueOf(quantity.value())));
    }

    @Override
    public int compareTo(Price other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
