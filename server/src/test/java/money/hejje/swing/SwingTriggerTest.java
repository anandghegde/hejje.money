package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import money.hejje.ratings.BaseType;
import org.junit.jupiter.api.Test;

/** Plan M11.4: the pivot cross in the buy zone on volume pace, the LIMIT cap, reversals without volume, Kite's stop distance. */
class SwingTriggerTest {

    static final SwingTrigger.Params PARAMS = new SwingTrigger.Params(new BigDecimal("1.4"), 20, new BigDecimal("0.25"), 375);
    static final BigDecimal TICK = new BigDecimal("0.05");

    static SwingTrigger.Setup setup(BaseType type, String stop, Long avg) {
        return new SwingTrigger.Setup(UUID.randomUUID(), UUID.randomUUID(), "NSE:X", type, new BigDecimal("100.00"), new BigDecimal("105.00"),
                new BigDecimal(stop), new BigDecimal("120.00"), avg);
    }

    static SwingTrigger.Result at(SwingTrigger.Setup s, String close, long volume, int minutes) {
        return SwingTrigger.evaluate(s, new BigDecimal(close), volume, minutes, PARAMS, TICK);
    }

    @Test
    void aBaseTriggersOnAPivotCrossInTheBuyZoneWithVolumePace() {
        SwingTrigger.Setup base = setup(BaseType.FLAT_BASE, "93.00", 100_000L);
        assertThat(at(base, "99.95", 50_000, 30).verdict()).isEqualTo(SwingTrigger.Verdict.WAIT);
        // 30 minutes, 12,000 shares: projected 1,50,000 = 1.5x the average
        SwingTrigger.Result r = at(base, "100.75", 12_000, 30);
        assertThat(r.verdict()).isEqualTo(SwingTrigger.Verdict.TRIGGER);
        assertThat(r.pace()).isEqualByComparingTo("1.50");
        assertThat(r.limit()).isEqualByComparingTo("100.95"); // 100.75 + 20 bps = 100.9515, on the tick grid
        assertThat(at(base, "100.75", 11_000, 30).verdict()).isEqualTo(SwingTrigger.Verdict.NO_VOLUME); // 1.37x
        assertThat(at(setup(BaseType.FLAT_BASE, "93.00", null), "100.75", 90_000, 30).verdict()).isEqualTo(SwingTrigger.Verdict.NO_VOLUME);
    }

    @Test
    void theLimitNeverExceedsTheBuyZoneAndAboveItNothingTriggers() {
        SwingTrigger.Setup base = setup(BaseType.FLAT_BASE, "93.00", 100_000L);
        assertThat(at(base, "104.95", 90_000, 30).limit()).isEqualByComparingTo("105.00");
        assertThat(at(base, "105.05", 90_000, 30).verdict()).isEqualTo(SwingTrigger.Verdict.ABOVE_BUY_ZONE);
    }

    @Test
    void aReversalHasNoVolumeConditionButItsStopMustBeFarEnoughForKite() {
        assertThat(at(setup(BaseType.MA_REVERSAL, "98.00", null), "100.20", 10, 30).verdict()).isEqualTo(SwingTrigger.Verdict.TRIGGER);
        // 100.20 - 100.00 = 0.20 % < 0.25 %
        assertThat(at(setup(BaseType.MA_REVERSAL, "100.00", null), "100.20", 10, 30).verdict()).isEqualTo(SwingTrigger.Verdict.STOP_TOO_NEAR);
    }
}
