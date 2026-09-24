package money.hejje.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import money.hejje.calibration.internal.CalibrationMath;
import money.hejje.calibration.internal.CalibrationMath.Point;
import org.junit.jupiter.api.Test;

/** Buckets, Wilson, Brier, ECE and the pass bar (plan M9.2). */
class CalibrationReportTest {

    static final CalibrationProperties PROPS = new CalibrationProperties(30, 60, 15, 20, 300, 15, 0.07, 5);

    /** {@code n} points at probability {@code p} with exactly {@code hits} of them hits. */
    static List<Point> points(double p, int n, int hits) {
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Point(p, i < hits));
        }
        return out;
    }

    @Test
    void aPerfectlyCalibratedSourceScoresEceZeroAndPasses() {
        List<Point> all = new ArrayList<>();
        for (int b = 0; b < 10; b++) {
            double p = 0.05 + 0.1 * b;
            all.addAll(points(p, 100, (int) Math.round(p * 100)));
        }
        CalibrationReport r = CalibrationService.report("synthetic", "1", null, null, all, 0, 0, 20, PROPS);
        assertThat(r.ece()).isCloseTo(0.0, within(1e-9));
        assertThat(r.n()).isEqualTo(1000);
        assertThat(r.buckets()).hasSize(10).allSatisfy(b -> assertThat(b.rate()).isNotNull());
        assertThat(r.topVsBottom().separated()).isTrue();
        assertThat(r.passes()).as("%s", r.reasons()).isTrue();
    }

    @Test
    void aConstantNinetyPercentSourceOnCoinFlipsFails() {
        CalibrationReport r = CalibrationService.report("constant", "1", null, null, points(0.9, 600, 300), 0, 0, 30, PROPS);
        assertThat(r.ece()).isCloseTo(0.4, within(1e-9));
        assertThat(r.topVsBottom().separated()).isFalse(); // one populated bucket: nothing to separate
        assertThat(r.passes()).isFalse();
        assertThat(r.reasons()).anyMatch(s -> s.contains("expected calibration error")).anyMatch(s -> s.contains("Wilson"));
    }

    @Test
    void tooFewAnswersOrSessionsFailEvenWhenCalibrated() {
        List<Point> all = new ArrayList<>(points(0.15, 50, 7));
        all.addAll(points(0.85, 50, 43));
        CalibrationReport r = CalibrationService.report("small", "1", null, null, all, 3, 2, 4, PROPS);
        assertThat(r.passes()).isFalse();
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("100 labelled")).anyMatch(s -> s.startsWith("4 sessions"));
        assertThat(r.none()).isEqualTo(3);
        assertThat(r.pending()).isEqualTo(2);
    }

    @Test
    void bucketsUnderTheMinimumShowTheirCountButNoRate() {
        List<CalibrationReport.Bucket> b = CalibrationMath.buckets(points(0.42, 19, 10), 20);
        assertThat(b.get(4).n()).isEqualTo(19);
        assertThat(b.get(4).hits()).isEqualTo(10);
        assertThat(b.get(4).rate()).isNull();
        assertThat(b.get(4).wilsonLo()).isNull();
        assertThat(CalibrationMath.bucketOf(1.0)).isEqualTo(9);
        assertThat(CalibrationMath.bucketOf(0.0)).isZero();
        assertThat(CalibrationMath.bucketOf(0.1)).isEqualTo(1);
    }

    @Test
    void wilsonAndBrierMatchHandComputedValues() {
        double[] w = CalibrationMath.wilson(50, 100); // 0.5 ± ~0.0960
        assertThat(w[0]).isCloseTo(0.4038, within(1e-3));
        assertThat(w[1]).isCloseTo(0.5962, within(1e-3));
        double[] w0 = CalibrationMath.wilson(0, 20);
        assertThat(w0[0]).isZero();
        assertThat(w0[1]).isCloseTo(0.1611, within(1e-3));
        // Brier of {0.8 hit, 0.3 miss} = ((0.2)² + (0.3)²) / 2 = 0.065
        assertThat(CalibrationMath.brier(List.of(new Point(0.8, true), new Point(0.3, false)))).isCloseTo(0.065, within(1e-9));
        assertThat(CalibrationMath.brier(List.of())).isNull();
    }
}
