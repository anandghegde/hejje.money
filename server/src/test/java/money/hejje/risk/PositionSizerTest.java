package money.hejje.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import money.hejje.common.Money;
import money.hejje.common.Price;
import org.junit.jupiter.api.Test;

class PositionSizerTest {

    @Test
    void floorsToLotSize() {
        // risk 2000, per-unit risk 45 -> 44 units -> lot 75 -> 0 lots (44<75) -> 0
        assertThat(PositionSizer.size(Price.of("24980.00"), Price.of("24935.00"), Money.ofRupees(2000), 75, 0)).isZero();
        // risk 10000, per-unit risk 45 -> 222 units -> 2 lots of 75 = 150
        assertThat(PositionSizer.size(Price.of("24980.00"), Price.of("24935.00"), Money.ofRupees(10000), 75, 0)).isEqualTo(150);
        // equities: lot 1, risk 1000, per-unit 5 -> 200
        assertThat(PositionSizer.size(Price.of("100.00"), Price.of("95.00"), Money.ofRupees(1000), 1, 0)).isEqualTo(200);
    }

    @Test
    void respectsMaxQuantity() {
        assertThat(PositionSizer.size(Price.of("100.00"), Price.of("95.00"), Money.ofRupees(1000), 1, 150)).isEqualTo(150);
        assertThat(PositionSizer.size(Price.of("24980.00"), Price.of("24935.00"), Money.ofRupees(100000), 75, 300)).isEqualTo(300);
    }

    @Test
    void rejectsEqualEntryAndStop() {
        assertThatThrownBy(() -> PositionSizer.size(Price.of("100.00"), Price.of("100.00"), Money.ofRupees(1000), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
