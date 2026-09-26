package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Plan M11.2 trailing: breakeven at +1R, then one tick under the 20-day low; never lower than the stop in force. */
class SwingTrailTest {

    static final BigDecimal ENTRY = new BigDecimal("100.00");
    static final BigDecimal STOP = new BigDecimal("93.00");
    static final BigDecimal TICK = new BigDecimal("0.05");

    static BigDecimal next(String current, String close, String low20) {
        return SwingTrail.next(ENTRY, STOP, new BigDecimal(current), new BigDecimal(close), low20 == null ? null : new BigDecimal(low20), TICK);
    }

    @Test
    void belowOneRTheStopStays() {
        assertThat(next("93.00", "106.95", "98.00")).isEqualByComparingTo("93.00");
    }

    @Test
    void atOneRTheStopMovesToBreakevenThenUnderTheTwentyDayLow() {
        assertThat(next("93.00", "107.00", "96.00")).isEqualByComparingTo("100.00"); // the 20-day low is still below breakeven
        assertThat(next("93.00", "112.00", "104.00")).isEqualByComparingTo("103.95");
        assertThat(next("100.00", "105.00", "101.00")).isEqualByComparingTo("100.95"); // at breakeven it keeps trailing
    }

    @Test
    void theStopNeverLoosens() {
        assertThat(next("103.95", "101.00", "98.00")).isEqualByComparingTo("103.95");
        assertThat(next("100.00", "99.00", null)).isEqualByComparingTo("100.00");
    }
}
