package money.hejje.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import money.hejje.common.OptionType;
import money.hejje.strategy.StrategyDefinition;
import org.junit.jupiter.api.Test;

/** Strike selection and premium levels (plan M5.4). */
class OptionLegResolverTest {

    static final OptionsProperties PROPS = new OptionsProperties(0.065, 0.15, 10, 50_000, java.time.LocalTime.of(13, 0), 30, java.time.Duration.ofSeconds(5), null);

    static OptionChain.OptionQuote q(double delta) {
        return new OptionChain.OptionQuote(UUID.randomUUID(), "x", 75, new BigDecimal("100.00"), null, null, 0, 0, 0.1, delta, 0.001, 10.0, -5.0, false);
    }

    static OptionChain chain() {
        List<OptionChain.Row> rows = List.of(
                new OptionChain.Row(new BigDecimal("24800.00"), q(0.80), q(-0.20)),
                new OptionChain.Row(new BigDecimal("24900.00"), q(0.65), q(-0.35)),
                new OptionChain.Row(new BigDecimal("25000.00"), q(0.52), q(-0.48)),
                new OptionChain.Row(new BigDecimal("25100.00"), q(0.38), q(-0.62)),
                new OptionChain.Row(new BigDecimal("25200.00"), q(0.24), q(-0.76)));
        return new OptionChain("NIFTY", LocalDate.of(2026, 9, 15), Instant.EPOCH, new BigDecimal("25010"), "FUT", 0.014, new BigDecimal("25000.00"), null, null,
                null, rows, List.of());
    }

    final OptionLegResolver resolver = new OptionLegResolver(null, null, PROPS, null);

    @Test
    void atmOffsetAndDeltaPickTheExpectedStrikes() {
        OptionChain c = chain();
        assertThat(resolver.pick(c, OptionType.CE, StrategyDefinition.StrikeSelector.ATM)).isSameAs(c.rows().get(2).call());
        StrategyDefinition.StrikeSelector plus100 = new StrategyDefinition.StrikeSelector(StrategyDefinition.StrikeKind.OFFSET, new BigDecimal("100"));
        assertThat(resolver.pick(c, OptionType.CE, plus100)).isSameAs(c.rows().get(3).call()); // calls: out of the money is higher
        assertThat(resolver.pick(c, OptionType.PE, plus100)).isSameAs(c.rows().get(1).put()); // puts: lower
        StrategyDefinition.StrikeSelector offGrid = new StrategyDefinition.StrikeSelector(StrategyDefinition.StrikeKind.OFFSET, new BigDecimal("140"));
        assertThat(resolver.pick(c, OptionType.CE, offGrid)).isSameAs(c.rows().get(3).call()); // 25140: the nearest listed strike is 25100 (40 away, 25200 is 60)
    }

    @Test
    void deltaTargetsUseAbsoluteDelta() {
        OptionChain c = chain();
        StrategyDefinition.StrikeSelector d30 = new StrategyDefinition.StrikeSelector(StrategyDefinition.StrikeKind.DELTA, new BigDecimal("0.3"));
        assertThat(resolver.pick(c, OptionType.CE, d30)).isSameAs(c.rows().get(4).call()); // 0.24 is 0.06 away, 0.38 is 0.08 away
        assertThat(resolver.pick(c, OptionType.PE, d30)).isSameAs(c.rows().get(1).put()); // |-0.35|
    }

    @Test
    void premiumLevelsRoundToTheTickAndNeverVanish() {
        assertThat(OptionLegResolver.level(new BigDecimal("120.30"), new BigDecimal("30"), -1, new BigDecimal("0.05"))).isEqualByComparingTo("84.20");
        assertThat(OptionLegResolver.level(new BigDecimal("120.30"), new BigDecimal("60"), 1, new BigDecimal("0.05"))).isEqualByComparingTo("192.50");
        assertThat(OptionLegResolver.level(new BigDecimal("0.10"), new BigDecimal("100"), -1, new BigDecimal("0.05"))).isEqualByComparingTo("0.05");
    }
}
