package money.hejje.market.indicators;

/** The last {@code n} values with O(1) mean and O(n) extremes/variance (windows are small). */
final class RollingWindow {

    private final double[] values;
    private int next;
    private int size;
    private double sum;

    RollingWindow(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("window must be >= 1");
        }
        this.values = new double[n];
    }

    void push(double value) {
        if (size == values.length) {
            sum -= values[next];
        } else {
            size++;
        }
        values[next] = value;
        sum += value;
        next = (next + 1) % values.length;
    }

    boolean isFull() {
        return size == values.length;
    }

    int size() {
        return size;
    }

    double mean() {
        // recompute to avoid drift from the running sum
        double s = 0;
        for (int i = 0; i < size; i++) {
            s += values[i];
        }
        return s / size;
    }

    /** Population standard deviation (ddof = 0), as TA-Lib's Bollinger bands use. */
    double populationStdDev() {
        double mean = mean();
        double acc = 0;
        for (int i = 0; i < size; i++) {
            double d = values[i] - mean;
            acc += d * d;
        }
        return Math.sqrt(acc / size);
    }

    double max() {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            m = Math.max(m, values[i]);
        }
        return m;
    }

    double min() {
        double m = Double.POSITIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            m = Math.min(m, values[i]);
        }
        return m;
    }
}
