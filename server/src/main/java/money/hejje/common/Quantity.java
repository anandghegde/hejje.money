package money.hejje.common;

/**
 * Number of units (shares or contracts). Strictly positive.
 *
 * @param value the count
 */
public record Quantity(@com.fasterxml.jackson.annotation.JsonValue int value) implements Comparable<Quantity> {

    public Quantity {
        if (value <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + value);
        }
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static Quantity of(int value) {
        return new Quantity(value);
    }

    /** True when this quantity is a whole number of lots of the given size. */
    public boolean isMultipleOf(int lotSize) {
        if (lotSize <= 0) {
            throw new IllegalArgumentException("Lot size must be positive: " + lotSize);
        }
        return value % lotSize == 0;
    }

    /** Returns this quantity if it is a whole number of lots, otherwise throws. */
    public Quantity requireLotMultiple(int lotSize) {
        if (!isMultipleOf(lotSize)) {
            throw new IllegalArgumentException("Quantity " + value + " is not a multiple of lot size " + lotSize);
        }
        return this;
    }

    public Quantity plus(Quantity other) {
        return new Quantity(Math.addExact(value, other.value));
    }

    @Override
    public int compareTo(Quantity other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
