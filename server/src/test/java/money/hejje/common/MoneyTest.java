package money.hejje.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesRupeesIntoPaise() {
        assertThat(Money.of("1500.00").paise()).isEqualTo(150_000);
        assertThat(Money.of("0.05").paise()).isEqualTo(5);
        assertThat(Money.of("-12.5").paise()).isEqualTo(-1250);
        assertThat(Money.of("7").paise()).isEqualTo(700);
    }

    @Test
    void rejectsFractionalPaise() {
        assertThatThrownBy(() -> Money.of("1.005")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fractional paise");
        assertThat(Money.of("1.0500").paise()).isEqualTo(105); // trailing zeros are fine
    }

    @Test
    void arithmetic() {
        Money a = Money.of("100.10");
        Money b = Money.of("0.15");
        assertThat(a.plus(b)).isEqualTo(Money.of("100.25"));
        assertThat(a.minus(b)).isEqualTo(Money.of("99.95"));
        assertThat(b.times(3)).isEqualTo(Money.of("0.45"));
        assertThat(a.minus(a).isZero()).isTrue();
        assertThat(b.minus(a).isNegative()).isTrue();
        assertThat(b.minus(a).abs()).isEqualTo(Money.of("99.95"));
    }

    @Test
    void decimalMultiplicationRoundsToWholePaise() {
        Money brokerage = Money.of("100.00").times(new BigDecimal("0.0003"), RoundingMode.HALF_EVEN);
        assertThat(brokerage).isEqualTo(Money.of("0.03"));
        assertThat(Money.of("0.05").times(new BigDecimal("0.5"), RoundingMode.HALF_EVEN)).isEqualTo(Money.of("0.02"));
        assertThat(Money.of("0.05").times(new BigDecimal("0.5"), RoundingMode.HALF_UP)).isEqualTo(Money.of("0.03"));
    }

    @Test
    void formatsWithTwoDecimals() {
        assertThat(Money.of("1500").toRupeesString()).isEqualTo("1500.00");
        assertThat(Money.ofPaise(-5).toRupeesString()).isEqualTo("-0.05");
        assertThat(Money.ZERO.toRupeesString()).isEqualTo("0.00");
    }

    @Test
    void overflowIsAnError() {
        assertThatThrownBy(() -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1))).isInstanceOf(ArithmeticException.class);
    }
}
