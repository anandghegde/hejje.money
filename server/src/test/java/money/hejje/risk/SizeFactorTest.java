package money.hejje.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;
import money.hejje.common.Money;
import money.hejje.common.Price;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The risk-event size cut (plan M9.7): half the risk money on a macro-event day, unchanged otherwise or when off. */
class SizeFactorTest {

    static final LocalDate EVENT_DAY = LocalDate.of(2026, 10, 1);

    @SuppressWarnings("unchecked")
    static RiskService service(String factor) {
        SizeFactorSource source = date -> date.equals(EVENT_DAY) ? Optional.of("RBI_POLICY: RBI policy (curated)") : Optional.empty();
        ObjectProvider<SizeFactorSource> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.orderedStream()).thenAnswer(i -> Stream.of(source));
        return new RiskService(null, null, null, null, null, null, null, null, provider, new BigDecimal(factor));
    }

    @Test
    void theFactorHalvesRiskAndQuantityOnlyOnAnEventDay() {
        RiskService risk = service("0.5");
        RiskService.SizeFactor event = risk.sizeFactor(EVENT_DAY);
        assertThat(event.factor()).isEqualByComparingTo("0.5");
        assertThat(event.event()).contains("RBI_POLICY");
        Money money = event.apply(Money.ofRupees(2000));
        assertThat(money).isEqualTo(Money.ofRupees(1000));
        int full = PositionSizer.size(Price.of("1000.00"), Price.of("990.00"), Money.ofRupees(2000), 1, 0);
        int cut = PositionSizer.size(Price.of("1000.00"), Price.of("990.00"), money, 1, 0);
        assertThat(full).isEqualTo(200);
        assertThat(cut).isEqualTo(100);
        assertThat(risk.sizeFactor(EVENT_DAY.plusDays(1))).isEqualTo(RiskService.SizeFactor.NONE);
    }

    @Test
    void theDefaultFactorOfOneIsOffAndTheFactorNeverRaisesSize() {
        assertThat(service("1.0").sizeFactor(EVENT_DAY)).isEqualTo(RiskService.SizeFactor.NONE);
        assertThat(RiskService.SizeFactor.NONE.apply(Money.ofRupees(2000))).isEqualTo(Money.ofRupees(2000));
        assertThatThrownBy(() -> service("1.5")).hasMessageContaining("only ever reduces size");
        assertThatThrownBy(() -> service("0")).isInstanceOf(IllegalArgumentException.class);
    }
}
