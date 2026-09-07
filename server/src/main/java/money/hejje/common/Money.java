package money.hejje.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amount of Indian rupees held as a whole number of paise. Never a floating point number.
 *
 * @param paise amount in paise (1 rupee = 100 paise); may be negative
 */
public record Money(long paise) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Parses a decimal rupee amount such as {@code "1500.00"} or {@code "-0.05"}. Rejects fractional paise. */
    public static Money of(String rupees) {
        return of(new BigDecimal(rupees));
    }

    public static Money of(BigDecimal rupees) {
        BigDecimal scaled = rupees.stripTrailingZeros();
        if (scaled.scale() > 2) {
            throw new IllegalArgumentException("Money has fractional paise: " + rupees.toPlainString());
        }
        return new Money(rupees.movePointRight(2).longValueExact());
    }

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    public static Money ofRupees(long rupees) {
        return new Money(Math.multiplyExact(rupees, 100L));
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    /** Exact multiplication by a whole number (for example a quantity). */
    public Money times(long factor) {
        return new Money(Math.multiplyExact(paise, factor));
    }

    /** Multiplication by a decimal factor (for example a percentage), rounded to whole paise. */
    public Money times(BigDecimal factor, RoundingMode rounding) {
        return new Money(BigDecimal.valueOf(paise).multiply(factor).setScale(0, rounding).longValueExact());
    }

    public Money negate() {
        return new Money(Math.negateExact(paise));
    }

    public Money abs() {
        return paise < 0 ? negate() : this;
    }

    public boolean isNegative() {
        return paise < 0;
    }

    public boolean isZero() {
        return paise == 0;
    }

    public BigDecimal toRupees() {
        return BigDecimal.valueOf(paise).divide(HUNDRED, 2, RoundingMode.UNNECESSARY);
    }

    /** Fixed two-decimal rupee string, for example {@code "1500.00"} or {@code "-0.05"}. */
    public String toRupeesString() {
        return toRupees().toPlainString();
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(paise, other.paise);
    }

    @Override
    public String toString() {
        return "INR " + toRupeesString();
    }
}
