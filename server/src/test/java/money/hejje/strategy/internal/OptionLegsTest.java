package money.hejje.strategy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyProperties;
import money.hejje.strategy.StrategyValidationException;
import money.hejje.strategy.ValidationError;
import org.junit.jupiter.api.Test;

/** The options legs of the strategy DSL (plan M5.4). */
class OptionLegsTest {

    final DefinitionParser parser = new DefinitionParser();
    final StrategyValidator validator = new StrategyValidator(new StrategyProperties(true, List.of(), Map.of("NIFTY", "nearest_future: NIFTY"), false));

    static String withLegs(String family, String legs) {
        return "name: legs_test\nfamily: " + family + "\nuniverse: [NIFTY]\ntimeframe: 5m\ndirection: long\nentry:\n  all:\n    - close > opening_range_high(15m)\n"
                + "stop:\n  type: opening_range_low\n" + legs;
    }

    @Test
    void theBundledSpreadParsesIntoLegsAndACombinedExit() throws Exception {
        StrategyDefinition d = parser.parse(Files.readString(Path.of("../strategies/nifty_bull_call_spread.yaml")));
        assertThat(d.legs()).hasSize(2);
        StrategyDefinition.OptionLeg hedge = d.legs().get(0);
        assertThat(hedge.action()).isEqualTo(StrategyDefinition.LegAction.BUY);
        assertThat(hedge.option()).isEqualTo(StrategyDefinition.OptionSide.DIRECTIONAL);
        assertThat(hedge.strike()).isEqualTo(StrategyDefinition.StrikeSelector.ATM);
        assertThat(hedge.expiry()).isEqualTo(StrategyDefinition.ExpirySelector.NEAREST);
        assertThat(hedge.hedgeFirst()).isTrue();
        StrategyDefinition.OptionLeg shortCall = d.legs().get(1);
        assertThat(shortCall.action()).isEqualTo(StrategyDefinition.LegAction.SELL);
        assertThat(shortCall.strike().kind()).isEqualTo(StrategyDefinition.StrikeKind.OFFSET);
        assertThat(shortCall.strike().value()).isEqualByComparingTo("100");
        assertThat(d.combinedExit().stopRupees().toRupeesString()).isEqualTo("2500.00");
        assertThat(validator.validate(d)).isEmpty();
    }

    @Test
    void structuralErrorsArePointedAtTheLeg() {
        String bad = withLegs("options", """
                legs:
                  - action: sell
                    hedge_first: true
                    lots: 0
                    strike: {offset: 100, delta: 0.3}
                  - action: hold
                    stop_pct: 150
                    colour: blue
                combined_exit: {}
                """);
        assertThatThrownBy(() -> parser.parse(bad)).isInstanceOf(StrategyValidationException.class).satisfies(e -> {
            List<ValidationError> errors = ((StrategyValidationException) e).errors();
            assertThat(errors).extracting(ValidationError::path).contains("legs[0].hedge_first", "legs[0].lots", "legs[0].strike", "legs[1].action",
                    "legs[1].stop_pct", "legs[1].colour", "combined_exit");
        });
    }

    @Test
    void legsBelongToOptionsStrategiesAndOptionsStrategiesNeedLegs() {
        StrategyDefinition legsOnTrend = parser.parse(withLegs("trend", "legs:\n  - action: buy\n"));
        assertThat(validator.validate(legsOnTrend)).extracting(ValidationError::path).contains("legs");
        StrategyDefinition optionsWithoutLegs = parser.parse(withLegs("options", ""));
        assertThat(validator.validate(optionsWithoutLegs)).extracting(ValidationError::message).anyMatch(m -> m.contains("needs legs"));
        StrategyDefinition delta = parser.parse(withLegs("options", "legs:\n  - action: buy\n    strike: {delta: 0.3}\n    expiry: next\n    option: pe\n"));
        assertThat(delta.legs().get(0).strike().kind()).isEqualTo(StrategyDefinition.StrikeKind.DELTA);
        assertThat(delta.legs().get(0).option()).isEqualTo(StrategyDefinition.OptionSide.PE);
        assertThat(validator.validate(delta)).isEmpty();
    }

    @Test
    void definitionsWithoutLegsSerialiseExactlyAsBeforeSoTheirHashesStay() throws Exception {
        JsonMapper canonical = JsonMapper.builder().addModule(new JavaTimeModule()).enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        String plain = canonical.writeValueAsString(parser.parse(Files.readString(Path.of("../strategies/nifty_orb.yaml"))));
        assertThat(plain).doesNotContain("\"legs\"").doesNotContain("\"combinedExit\"");
        String options = canonical.writeValueAsString(parser.parse(Files.readString(Path.of("../strategies/nifty_bull_call_spread.yaml"))));
        assertThat(options).contains("\"legs\"").contains("\"combinedExit\"");
    }

    @Test
    void neutralIsForOptionsLegsThatNameTheirTypeAndABandStop() throws Exception {
        StrategyDefinition fly = parser.parse(Files.readString(Path.of("../strategies/nifty_920_iron_fly.yaml")));
        assertThat(fly.direction()).isEqualTo(StrategyDefinition.Direction.NEUTRAL);
        assertThat(fly.legs()).extracting(StrategyDefinition.OptionLeg::option).containsExactly(StrategyDefinition.OptionSide.CE, StrategyDefinition.OptionSide.PE,
                StrategyDefinition.OptionSide.CE, StrategyDefinition.OptionSide.PE);
        assertThat(validator.validate(fly)).isEmpty();

        String neutral = "name: neutral_test\nfamily: %s\nuniverse: [NIFTY]\ntimeframe: 5m\ndirection: neutral\nentry:\n  all:\n    - session_minutes >= 5\n"
                + "stop:\n  type: %s\n%s";
        // neutral only with family options
        StrategyDefinition onTrend = parser.parse(neutral.formatted("trend", "points\n  value: 150", ""));
        assertThat(validator.validate(onTrend)).extracting(ValidationError::path).contains("direction");
        // directional and opposite legs follow a side the signal does not have
        StrategyDefinition directional = parser.parse(neutral.formatted("options", "points\n  value: 150",
                "legs:\n  - action: buy\n    option: directional\n  - action: sell\n    option: opposite\n  - action: sell\n    option: ce\n"));
        assertThat(validator.validate(directional)).extracting(ValidationError::path).containsExactly("legs[0].option", "legs[1].option");
        // a one-sided stop cannot be a band
        StrategyDefinition oneSided = parser.parse(neutral.formatted("options", "opening_range_low", "legs:\n  - action: buy\n    option: ce\n"));
        assertThat(validator.validate(oneSided)).extracting(ValidationError::path).containsExactly("stop.type");
        assertThat(money.hejje.strategy.RuleWords.describe(fly).get(0)).startsWith("Neutral (no side on the underlying) on ");
    }
}
