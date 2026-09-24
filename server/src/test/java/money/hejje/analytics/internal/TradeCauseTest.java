package money.hejje.analytics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.analytics.TradeCause;
import money.hejje.analytics.TradeCause.Cause;
import money.hejje.analytics.TradeCause.Timing;
import money.hejje.analytics.TradeCauseProperties;
import money.hejje.common.Timeframe;
import money.hejje.market.Candle;
import org.junit.jupiter.api.Test;

/** One fixture trade per cause, the timing rules, repeatability and the partial window (plan M9.6). */
class TradeCauseTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final UUID ID = UUID.randomUUID();
    static final TradeCauseProperties P = new TradeCauseProperties(1.5, 2.0, -0.5, 1.0, 0.3, -0.7, 0.5, 0.3, 0.2, 15, 30, 30);
    static final Instant SESSION_CLOSE = at("15:30");

    static Instant at(String hhmm) {
        return LocalDateTime.parse("2026-09-23T" + hhmm).atZone(IST).toInstant();
    }

    /** Minute bars from 09:15 following {@code path} (close of each minute); high/low = close ± 0.5, open = previous close. */
    static List<Candle> bars(double... path) {
        List<Candle> out = new ArrayList<>();
        double prev = path[0];
        for (int i = 0; i < path.length; i++) {
            double c = path[i];
            double hi = Math.max(prev, c) + 0.5;
            double lo = Math.min(prev, c) - 0.5;
            out.add(new Candle(ID, Timeframe.M1, at("09:15").plusSeconds(60L * i), bd(prev), bd(hi), bd(lo), bd(c), 1000, 0, false));
            prev = c;
        }
        return out;
    }

    /** A flat 100 for {@code n} minutes, then the given path. */
    static double[] flatThen(int n, double... rest) {
        double[] out = new double[n + rest.length];
        java.util.Arrays.fill(out, 0, n, 100);
        System.arraycopy(rest, 0, out, n, rest.length);
        return out;
    }

    static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** A long entered at 100 (minute 45, 10:00) with stop 98 (R = 2), closed as given; now = the next day. */
    static TradeCauseClassifier.Trade longTrade(double exit, String reason, String closedAt) {
        return new TradeCauseClassifier.Trade(true, bd(100), bd(exit), bd(98), reason, at("10:00"), at(closedAt), SESSION_CLOSE,
                Instant.parse("2026-09-24T04:00:00Z"));
    }

    @Test
    void cleanTarget() {
        // entry 10:00 at 100, a steady climb to the target 104 by 10:08; MAE stays above −0.5R
        double[] path = flatThen(45, 100.5, 101, 101.5, 102, 102.5, 103, 103.5, 104, 104, 104);
        TradeCause c = TradeCauseClassifier.classify(longTrade(104, "TARGET", "10:09"), bars(path), P);
        assertThat(c.cause()).isEqualTo(Cause.CLEAN_TARGET);
        assertThat(c.mfeR()).isGreaterThanOrEqualTo(2.0);
        assertThat(c.maeR()).isGreaterThan(-0.5);
        assertThat(c.entryTiming()).isEqualTo(Timing.GOOD);
    }

    @Test
    void noiseStop() {
        // dips to the stop by 10:03, then within 30 minutes regains 100 and reaches +1R (102)
        double[] path = flatThen(45, 99.5, 98.8, 98.2, 97.8, 99, 100, 101, 102, 102.5, 103);
        TradeCause c = TradeCauseClassifier.classify(longTrade(98, "STOP", "10:04"), bars(path), P);
        assertThat(c.cause()).isEqualTo(Cause.NOISE_STOP);
        assertThat(c.evidence()).containsKey("postExitBestR");
        assertThat(c.entryTiming()).isEqualTo(Timing.EARLY);
    }

    @Test
    void thesisBreak() {
        // stopped and never recovers
        double[] path = flatThen(45, 99.5, 98.8, 98.2, 97.8, 97, 96.5, 96, 96, 95.5, 95);
        TradeCause stopped = TradeCauseClassifier.classify(longTrade(98, "STOP", "10:04"), bars(path), P);
        assertThat(stopped.cause()).isEqualTo(Cause.THESIS_BREAK);
        // an exit rule (or a bot's exit) is a thesis break too
        double[] flat = flatThen(45, 100.2, 100.4, 100.1, 99.9, 99.8, 99.9);
        assertThat(TradeCauseClassifier.classify(longTrade(99.8, "RULE_EXIT", "10:05"), bars(flat), P).cause()).isEqualTo(Cause.THESIS_BREAK);
        assertThat(TradeCauseClassifier.classify(longTrade(99.8, "MANUAL", "10:05"), bars(flat), P).cause()).isEqualTo(Cause.THESIS_BREAK);
    }

    @Test
    void drift() {
        double[] path = flatThen(45, 100.2, 100.1, 100.3, 100.2, 100.1, 100.2, 100.3, 100.2);
        TradeCause c = TradeCauseClassifier.classify(longTrade(100.2, "MAX_HOLDING", "10:08"), bars(path), P);
        assertThat(c.cause()).isEqualTo(Cause.DRIFT);
        assertThat(TradeCauseClassifier.classify(longTrade(100.2, "FORCE_EXIT", "10:08"), bars(path), P).cause()).isEqualTo(Cause.DRIFT);
    }

    @Test
    void badEntryComesFirst() {
        // a 5-point run-up in the 15 minutes before the 10:00 entry (ATR about 1): stretched, even though the target was hit
        double[] path = flatThen(30, 100.3, 100.6, 100.9, 101.2, 101.5, 101.8, 102.1, 102.4, 102.7, 103, 103.3, 103.6, 103.9, 104.2, 105,
                105.5, 106, 106.5, 107, 107.5, 108, 108.5, 109, 109);
        TradeCauseClassifier.Trade t = new TradeCauseClassifier.Trade(true, bd(105), bd(109), bd(103), "TARGET", at("10:00"), at("10:09"), SESSION_CLOSE,
                Instant.parse("2026-09-24T04:00:00Z"));
        TradeCause c = TradeCauseClassifier.classify(t, bars(path), P);
        assertThat(c.cause()).isEqualTo(Cause.BAD_ENTRY);
        assertThat(((Number) c.evidence().get("preEntryMoveAtr")).doubleValue()).isGreaterThanOrEqualTo(1.5);
    }

    @Test
    void lateEntryAtTheTopOfTheRangeThatGoesNowhere() {
        // a rise to 102 by 09:59 (entry at the top of the 30-minute range), then flat: MFE under 0.3R
        double[] path = flatThen(15, 100.1, 100.2, 100.3, 100.4, 100.5, 100.6, 100.7, 100.8, 100.9, 101, 101.1, 101.2, 101.3, 101.4, 101.5, 101.6,
                101.7, 101.8, 101.9, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 101.9, 101.9, 101.9, 101.8, 101.9);
        TradeCauseClassifier.Trade t = new TradeCauseClassifier.Trade(true, bd(102), bd(101.9), bd(100), "MAX_HOLDING", at("10:00"), at("10:05"),
                SESSION_CLOSE, Instant.parse("2026-09-24T04:00:00Z"));
        TradeCause c = TradeCauseClassifier.classify(t, bars(path), new TradeCauseProperties(10, 10, -0.5, 1.0, 0.3, -0.7, 0.5, 0.3, 0.2, 15, 30, 30));
        assertThat(c.entryTiming()).isEqualTo(Timing.LATE);
        assertThat(c.cause()).isEqualTo(Cause.DRIFT);
    }

    @Test
    void theSameCandlesGiveTheSameAnswer() {
        double[] path = flatThen(45, 99.5, 98.8, 98.2, 97.8, 99, 100, 101, 102, 102.5, 103);
        assertThat(TradeCauseClassifier.classify(longTrade(98, "STOP", "10:04"), bars(path), P))
                .isEqualTo(TradeCauseClassifier.classify(longTrade(98, "STOP", "10:04"), bars(path), P));
    }

    @Test
    void aTradeClosedNearTheSessionEndIsCompletedFromWhatExistsWithAPartialWindow() {
        double[] path = new double[375];
        java.util.Arrays.fill(path, 100);
        TradeCauseClassifier.Trade t = new TradeCauseClassifier.Trade(true, bd(100), bd(98), bd(98), "STOP", at("15:00"), at("15:10"), SESSION_CLOSE,
                at("15:35"));
        TradeCause c = TradeCauseClassifier.classify(t, bars(path), P);
        assertThat(c.evidence()).containsEntry("partialWindow", true);
        assertThat(c.complete()).isTrue(); // the session has closed: nothing more will come
        assertThat(c.cause()).isEqualTo(Cause.THESIS_BREAK);
    }

    @Test
    void withoutAStopTheCauseIsUnknown() {
        TradeCauseClassifier.Trade t = new TradeCauseClassifier.Trade(true, bd(100), bd(101), null, null, at("10:00"), at("10:05"), SESSION_CLOSE,
                at("11:00"));
        assertThat(TradeCauseClassifier.classify(t, bars(100, 100), P).cause()).isEqualTo(Cause.UNKNOWN);
    }
}
