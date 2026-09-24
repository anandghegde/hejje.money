package money.hejje.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.calibration.internal.Labeler;
import money.hejje.calibration.internal.Labeler.Outcome;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.market.Candle;
import org.junit.jupiter.api.Test;

/** The outcome rules of docs/calibration.md on hand-built M1 bars (plan M9.2). */
class CalibrationLabelerTest {

    static final UUID ID = UUID.randomUUID();
    static final Instant T0 = Instant.parse("2026-12-02T04:30:00Z"); // 10:00 IST

    /** Bars from T0, one minute apart, as {open, high, low, close}. */
    static List<Candle> bars(double[]... ohlc) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) {
            double[] b = ohlc[i];
            out.add(new Candle(ID, Timeframe.M1, T0.plusSeconds(60L * i), bd(b[0]), bd(b[1]), bd(b[2]), bd(b[3]), 100, 0, false));
        }
        return out;
    }

    static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2);
    }

    static Prediction entry(Side side, double stop) {
        return new Prediction("bot", "d1", "confidence", "bot:x", "1", 0.6, ID, LabelRule.ENTRY_1R, side, bd(stop), T0);
    }

    static Prediction of(LabelRule rule, Side side) {
        return new Prediction("jev", "c1", "k", "p", "1", 0.6, ID, rule, side, null, T0);
    }

    @Test
    void plusOneRFirstIsAHit() {
        // long from 100, stop 98: +1R = 102, −1R = 98
        Labeler.Label l = Labeler.entry(entry(Side.BUY, 98), bars(new double[] {100, 101, 99, 100.5}, new double[] {100.5, 102, 99.5, 101.8},
                new double[] {101.8, 101.9, 97, 97.5}));
        assertThat(l.outcome()).isEqualTo(Outcome.HIT);
        assertThat(l.evidence()).containsEntry("plus1R", bd(102)).containsEntry("decidedBy", T0.plusSeconds(60).toString());
    }

    @Test
    void minusOneRFirstIsAMiss() {
        Labeler.Label l = Labeler.entry(entry(Side.BUY, 98), bars(new double[] {100, 100.5, 97.9, 98.2}, new double[] {98.2, 103, 98, 102.5}));
        assertThat(l.outcome()).isEqualTo(Outcome.MISS);
    }

    @Test
    void bothInOneBarCountsAsMinusOneRFirst() {
        Labeler.Label l = Labeler.entry(entry(Side.SELL, 102), bars(new double[] {100, 102.1, 97.9, 100}));
        assertThat(l.outcome()).isEqualTo(Outcome.MISS);
        assertThat((String) l.evidence().get("reason")).contains("pessimistic");
    }

    @Test
    void neitherWithinTheWindowIsNone() {
        Labeler.Label l = Labeler.entry(entry(Side.SELL, 102), bars(new double[] {100, 101, 99, 100}, new double[] {100, 101.5, 98.5, 99}));
        assertThat(l.outcome()).isEqualTo(Outcome.NONE);
        assertThat(Labeler.entry(entry(Side.SELL, 102), List.of())).isNull();
    }

    @Test
    void aReferenceOpenAlreadyThroughTheStopIsAMiss() {
        Labeler.Label l = Labeler.entry(entry(Side.BUY, 98), bars(new double[] {97.5, 110, 97, 109}));
        assertThat(l.outcome()).isEqualTo(Outcome.MISS);
    }

    @Test
    void directionIsTheSignOfTheReturnAgainstTheSide() {
        List<Candle> up = bars(new double[] {100, 101, 99, 100.5}, new double[] {100.5, 102, 100, 101});
        assertThat(Labeler.direction(of(LabelRule.DIRECTION, Side.BUY), up).outcome()).isEqualTo(Outcome.HIT);
        assertThat(Labeler.direction(of(LabelRule.DIRECTION, Side.SELL), up).outcome()).isEqualTo(Outcome.MISS);
        List<Candle> flat = bars(new double[] {100, 101, 99, 100});
        assertThat(Labeler.direction(of(LabelRule.DIRECTION, Side.BUY), flat).outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    void anExitIsRightWhenTheAdverseMoveBeatsTheFavourableOne() {
        // long position: from 100 the price reaches 100.5 but falls to 98
        List<Candle> falls = bars(new double[] {100, 100.5, 99, 99.2}, new double[] {99.2, 99.5, 98, 98.4});
        assertThat(Labeler.exit(of(LabelRule.EXIT, Side.BUY), falls).outcome()).isEqualTo(Outcome.HIT);
        assertThat(Labeler.exit(of(LabelRule.EXIT, Side.SELL), falls).outcome()).isEqualTo(Outcome.MISS);
    }
}
