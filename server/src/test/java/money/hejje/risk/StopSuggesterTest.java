package money.hejje.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.OptionalDouble;
import money.hejje.common.Price;
import money.hejje.common.Side;
import org.junit.jupiter.api.Test;

class StopSuggesterTest {

    private static final BigDecimal MAX = new BigDecimal("5.00");
    private static final BigDecimal TICK = new BigDecimal("0.05");

    @Test
    void atrMultipleFromEntry() {
        StopSuggestion buy = StopSuggester.suggest(Side.BUY, Price.of("1500.00"), OptionalDouble.of(4.0), MAX, TICK);
        assertThat(buy.stop()).isEqualTo(Price.of("1494.00"));
        assertThat(buy.basis()).isEqualTo("ATR");
        assertThat(buy.atr()).isEqualByComparingTo("4");
        assertThat(buy.distancePct()).isEqualByComparingTo("0.40");
        assertThat(StopSuggester.suggest(Side.SELL, Price.of("1500.00"), OptionalDouble.of(4.0), MAX, TICK).stop()).isEqualTo(Price.of("1506.00"));
    }

    @Test
    void roundsToTickTowardsEntry() {
        // 1.5 x 4.01 = 6.015 -> 1493.985 -> next tick towards the entry
        assertThat(StopSuggester.suggest(Side.BUY, Price.of("1500.00"), OptionalDouble.of(4.01), MAX, TICK).stop()).isEqualTo(Price.of("1494.00"));
        assertThat(StopSuggester.suggest(Side.SELL, Price.of("1500.00"), OptionalDouble.of(4.01), MAX, TICK).stop()).isEqualTo(Price.of("1506.00"));
    }

    @Test
    void fallsBackToPercentWithoutBars() {
        StopSuggestion s = StopSuggester.suggest(Side.BUY, Price.of("1500.00"), OptionalDouble.empty(), MAX, TICK);
        assertThat(s.stop()).isEqualTo(Price.of("1485.00"));
        assertThat(s.basis()).isEqualTo("PERCENT");
        assertThat(s.atr()).isNull();
        assertThat(s.distancePct()).isEqualByComparingTo("1.00");
    }

    @Test
    void clampsToMaxStopDistance() {
        StopSuggestion s = StopSuggester.suggest(Side.BUY, Price.of("100.00"), OptionalDouble.of(10.0), MAX, TICK);
        assertThat(s.stop()).isEqualTo(Price.of("95.00"));
        assertThat(s.basis()).isEqualTo("MAX_DISTANCE");
        assertThat(s.distancePct()).isEqualByComparingTo("5.00");
        assertThat(StopSuggester.suggest(Side.SELL, Price.of("100.00"), OptionalDouble.of(10.0), MAX, TICK).stop()).isEqualTo(Price.of("105.00"));
    }

    @Test
    void neverEqualsTheEntry() {
        assertThat(StopSuggester.suggest(Side.BUY, Price.of("100.00"), OptionalDouble.of(0.001), MAX, TICK).stop()).isEqualTo(Price.of("99.95"));
        assertThat(StopSuggester.suggest(Side.SELL, Price.of("100.00"), OptionalDouble.of(0.001), MAX, TICK).stop()).isEqualTo(Price.of("100.05"));
    }
}
