package money.hejje.market.indicators;

import java.util.Arrays;
import java.util.OptionalDouble;

/** Fixed-capacity ring buffer of doubles; {@code NaN} marks "no value". Offset 0 is the most recent entry. */
public final class History {

    public static final int DEFAULT_CAPACITY = 1024;

    private final double[] values;
    private int next;
    private int size;

    public History() {
        this(DEFAULT_CAPACITY);
    }

    public History(int capacity) {
        this.values = new double[capacity];
        Arrays.fill(values, Double.NaN);
    }

    public void push(double value) {
        values[next] = value;
        next = (next + 1) % values.length;
        if (size < values.length) {
            size++;
        }
    }

    /** Raw value {@code offset} entries back, or NaN when missing or out of range. */
    public double raw(int offset) {
        if (offset < 0 || offset >= size) {
            return Double.NaN;
        }
        int index = ((next - 1 - offset) % values.length + values.length) % values.length;
        return values[index];
    }

    public OptionalDouble get(int offset) {
        double v = raw(offset);
        return Double.isNaN(v) ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    public int size() {
        return size;
    }
}
