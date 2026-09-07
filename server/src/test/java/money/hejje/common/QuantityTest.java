package money.hejje.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuantityTest {

    @Test
    void mustBePositive() {
        assertThatThrownBy(() -> Quantity.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Quantity.of(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Quantity.of(1).value()).isEqualTo(1);
    }

    @Test
    void lotSizeValidation() {
        assertThat(Quantity.of(150).isMultipleOf(75)).isTrue();
        assertThat(Quantity.of(100).isMultipleOf(75)).isFalse();
        assertThat(Quantity.of(75).requireLotMultiple(75)).isEqualTo(Quantity.of(75));
        assertThatThrownBy(() -> Quantity.of(100).requireLotMultiple(75)).isInstanceOf(IllegalArgumentException.class);
    }
}
