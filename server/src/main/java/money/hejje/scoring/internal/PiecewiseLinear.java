package money.hejje.scoring.internal;

/** Monotone piecewise-linear map from a metric to 0-100, defined by (x, y) knots; clamps outside the knots. */
public final class PiecewiseLinear {

    private final double[] xs;
    private final double[] ys;

    public PiecewiseLinear(double[] xs, double[] ys) {
        if (xs.length != ys.length || xs.length < 2) {
            throw new IllegalArgumentException("need matching knots");
        }
        this.xs = xs;
        this.ys = ys;
    }

    public double apply(double x) {
        if (Double.isNaN(x)) {
            return 0;
        }
        if (x <= xs[0]) {
            return ys[0];
        }
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) {
                double t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
                return ys[i - 1] + t * (ys[i] - ys[i - 1]);
            }
        }
        return ys[ys.length - 1];
    }
}
