package money.hejje.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class PriceTest {

    static final BigDecimal TICK = new BigDecimal("0.05");

    @Test
    void parsesAndNormalisesScale() {
        assertThat(Price.of("24930.05").value()).isEqualByComparingTo("24930.05");
        assertThat(Price.of("100").value().scale()).isEqualTo(2);
        assertThat(Price.of("100").toString()).isEqualTo("100.00");
    }

    @Test
    void rejectsNonPositiveAndTooManyDecimals() {
        assertThatThrownBy(() -> Price.of("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Price.of("-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Price.of("1.005")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tickAlignment() {
        assertThat(Price.of("24930.05").isAlignedTo(TICK)).isTrue();
        assertThat(Price.of("24930.10").isAlignedTo(TICK)).isTrue();
        assertThat(Price.of("24930.07").isAlignedTo(TICK)).isFalse();
        assertThat(Price.of("24930.05").alignedTo(TICK)).isEqualTo(Price.of("24930.05"));
        assertThatThrownBy(() -> Price.of("24930.07").alignedTo(TICK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tick size");
        assertThat(Price.of("24930.07").roundedTo(TICK, RoundingMode.HALF_UP)).isEqualTo(Price.of("24930.05"));
        assertThat(Price.of("24930.08").roundedTo(TICK, RoundingMode.HALF_UP)).isEqualTo(Price.of("24930.10"));
    }

    @Test
    void notionalIsMoney() {
        assertThat(Price.of("99.95").times(Quantity.of(10))).isEqualTo(Money.of("999.50"));
    }
}
