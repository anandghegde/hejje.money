package money.hejje.strategy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import money.hejje.common.Product;
import money.hejje.common.Timeframe;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDefinition.Direction;
import money.hejje.strategy.StrategyDefinition.EventAction;
import money.hejje.strategy.StrategyDefinition.RegimePreference;
import money.hejje.strategy.StrategyDefinition.RuleMode;
import money.hejje.strategy.StrategyDefinition.StopType;
import money.hejje.strategy.StrategyDefinition.TargetType;
import money.hejje.strategy.StrategyDefinition.UniverseKind;
import money.hejje.strategy.StrategyFamily;
import money.hejje.strategy.StrategyProperties;
import money.hejje.strategy.StrategyValidationException;
import money.hejje.strategy.ValidationError;
import org.junit.jupiter.api.Test;

class DefinitionParserTest {

    /** PRD section 10, verbatim. */
    static final String PRD_EXAMPLE = """
            name: nifty_orb_vwap
            version: 3

            universe:
              - NIFTY

            timeframe: 5m

            entry:
              all:
                - close > opening_range_high
                - close > vwap
                - relative_volume > 1.4

            direction: long

            stop:
              type: opening_range_low

            target:
              type: risk_multiple
              value: 2.0

            trade_window:
              start: "09:30"
              end: "12:00"

            max_trades_per_day: 1

            regime_preferences:
              trending: preferred
              ranging: avoid

            event_rules:
              high_risk_event_within_minutes: 15
              action: block
            """;

    final DefinitionParser parser = new DefinitionParser();
    final StrategyValidator validator = new StrategyValidator(
            new StrategyProperties(true, List.of(), Map.of("NIFTY", "nearest_future: NIFTY"), false));

    private StrategyDefinition parseValid(String yaml) {
        StrategyDefinition d = parser.parse(yaml);
        assertThat(validator.validate(d)).isEmpty();
        return d;
    }

    private List<ValidationError> errorsOf(String yaml) {
        try {
            return validator.validate(parser.parse(yaml));
        } catch (StrategyValidationException e) {
            return e.errors();
        }
    }

    @Test
    void prdExampleParsesAndValidates() {
        StrategyDefinition d = parseValid(PRD_EXAMPLE);
        assertThat(d.name()).isEqualTo("nifty_orb_vwap");
        assertThat(d.family()).isEqualTo(StrategyFamily.TREND);
        assertThat(d.universe()).hasSize(1);
        assertThat(d.universe().get(0).kind()).isEqualTo(UniverseKind.ALIAS);
        assertThat(d.universe().get(0).value()).isEqualTo("NIFTY");
        assertThat(d.timeframe()).isEqualTo(Timeframe.M5);
        assertThat(d.direction()).isEqualTo(Direction.LONG);
        assertThat(d.entry().mode()).isEqualTo(RuleMode.ALL);
        assertThat(d.entry().conditions()).extracting(c -> c.text())
                .containsExactly("close > opening_range_high(15m)", "close > vwap", "relative_volume(20) > 1.4");
        assertThat(d.exit()).isNull();
        assertThat(d.stop().type()).isEqualTo(StopType.OPENING_RANGE_LOW);
        assertThat(d.target().type()).isEqualTo(TargetType.RISK_MULTIPLE);
        assertThat(d.target().value()).isEqualByComparingTo(new BigDecimal("2.0"));
        assertThat(d.tradeWindow().start()).isEqualTo(LocalTime.of(9, 30));
        assertThat(d.tradeWindow().end()).isEqualTo(LocalTime.of(12, 0));
        assertThat(d.forceExitTime()).isEqualTo(LocalTime.of(15, 10));
        assertThat(d.maxTradesPerDay()).isEqualTo(1);
        assertThat(d.product()).isEqualTo(Product.MIS);
        assertThat(d.regimePreferences()).containsEntry("trending", RegimePreference.PREFERRED).containsEntry("ranging", RegimePreference.AVOID);
        assertThat(d.eventRules().highRiskEventWithinMinutes()).isEqualTo(15);
        assertThat(d.eventRules().action()).isEqualTo(EventAction.BLOCK);
    }

    @Test
    void universeAcceptsSymbolsAndSelectors() {
        StrategyDefinition d = parseValid(PRD_EXAMPLE.replace("  - NIFTY\n", """
                  - "INDEX:NIFTY 50"
                  - NSE:INFY
                  - nearest_future: BANKNIFTY
                  - index: NIFTY BANK
                  - symbol: "NFO:NIFTY:FUT:2026-09-24"
                """));
        assertThat(d.universe()).extracting(u -> u.kind()).containsExactly(UniverseKind.SYMBOL, UniverseKind.SYMBOL,
                UniverseKind.NEAREST_FUTURE, UniverseKind.INDEX, UniverseKind.SYMBOL);
        assertThat(d.universe()).extracting(u -> u.value()).containsExactly("INDEX:NIFTY 50", "NSE:INFY", "BANKNIFTY", "NIFTY BANK",
                "NFO:NIFTY:FUT:2026-09-24");
    }

    @Test
    void reportsStructuralErrorsWithPaths() {
        List<ValidationError> errors = errorsOf("""
                name: Bad Name
                universe: []
                timeframe: 7m
                direction: sideways
                entry:
                  all:
                    - close > nope(3)
                    - close >
                  any: []
                stop:
                  type: atr_multiple
                target:
                  type: risk_multiple
                trade_window:
                  start: 09:30
                  end: "12:00"
                unknown_key: 1
                """);
        assertThat(errors).extracting(ValidationError::path).contains("name", "universe", "timeframe", "direction",
                "entry", "stop.value", "target.value", "unknown_key");
        // an unquoted 09:30 is read as text by the YAML parser, not as a sexagesimal number
        assertThat(errors).extracting(ValidationError::path).doesNotContain("trade_window.start");
    }

    @Test
    void conditionErrorsCarryListIndex() {
        List<ValidationError> errors = errorsOf(PRD_EXAMPLE.replace("- close > vwap", "- close > vwapp"));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path()).isEqualTo("entry.all[1]");
        assertThat(errors.get(0).message()).contains("Unknown indicator 'vwapp'");
    }

    @Test
    void malformedYamlIsOneError() {
        assertThatThrownBy(() -> parser.parse("name: [unclosed"))
                .isInstanceOf(StrategyValidationException.class)
                .satisfies(e -> assertThat(((StrategyValidationException) e).errors()).hasSize(1)
                        .first().extracting(ValidationError::message).asString().contains("Malformed YAML"));
        assertThatThrownBy(() -> parser.parse("- just\n- a list")).isInstanceOf(StrategyValidationException.class);
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(StrategyValidationException.class);
    }

    @Test
    void semanticRules() {
        // trade window outside the session
        assertThat(errorsOf(PRD_EXAMPLE.replace("start: \"09:30\"", "start: \"09:00\""))).extracting(ValidationError::path).contains("trade_window.start");
        assertThat(errorsOf(PRD_EXAMPLE.replace("end: \"12:00\"", "end: \"15:40\""))).extracting(ValidationError::path).contains("trade_window.end");
        assertThat(errorsOf(PRD_EXAMPLE.replace("end: \"12:00\"", "end: \"09:20\""))).extracting(ValidationError::path).contains("trade_window");
        // MIS force exit must be before 15:20
        assertThat(errorsOf(PRD_EXAMPLE + "force_exit_time: \"15:25\"\n")).extracting(ValidationError::path).contains("force_exit_time");
        assertThat(errorsOf(PRD_EXAMPLE + "force_exit_time: \"15:15\"\n")).isEmpty();
        // opening range must be a whole number of bars
        assertThat(errorsOf(PRD_EXAMPLE.replace("timeframe: 5m", "timeframe: 1h"))).extracting(ValidationError::path)
                .contains("entry.all[0]", "stop.type");
        assertThat(errorsOf(PRD_EXAMPLE.replace("timeframe: 5m", "timeframe: 15m"))).isEmpty();
        assertThat(errorsOf(PRD_EXAMPLE.replace("timeframe: 5m", "timeframe: 1d"))).extracting(ValidationError::path).contains("timeframe");
        // directional stop must match direction
        assertThat(errorsOf(PRD_EXAMPLE.replace("direction: long", "direction: short"))).extracting(ValidationError::path).contains("stop.type");
        assertThat(errorsOf(PRD_EXAMPLE.replace("direction: long", "direction: both"))).extracting(ValidationError::path).contains("stop.type");
        assertThat(errorsOf(PRD_EXAMPLE.replace("direction: long", "direction: both")
                .replace("type: opening_range_low", "type: atr_multiple\n  value: 1.5"))).isEmpty();
        // unknown alias
        assertThat(errorsOf(PRD_EXAMPLE.replace("- NIFTY", "- SENSEX"))).extracting(ValidationError::path).contains("universe[0]");
        // max trades
        assertThat(errorsOf(PRD_EXAMPLE.replace("max_trades_per_day: 1", "max_trades_per_day: 0"))).extracting(ValidationError::path).contains("max_trades_per_day");
        // event rules need minutes when blocking
        assertThat(errorsOf(PRD_EXAMPLE.replace("  high_risk_event_within_minutes: 15\n", ""))).extracting(ValidationError::path)
                .contains("event_rules.high_risk_event_within_minutes");
        // target below min reward:risk override
        assertThat(errorsOf(PRD_EXAMPLE + "risk_overrides:\n  min_reward_risk: 3\n")).extracting(ValidationError::path).contains("target.value");
    }

    @Test
    void defaultsApply() {
        StrategyDefinition d = parseValid("""
                name: minimal
                universe: [NSE:INFY]
                timeframe: 5m
                direction: short
                entry:
                  any:
                    - close < vwap
                stop:
                  type: percent
                  value: 0.5
                """);
        assertThat(d.family()).isEqualTo(StrategyFamily.TREND);
        assertThat(d.target().type()).isEqualTo(TargetType.NONE);
        assertThat(d.tradeWindow().start()).isEqualTo(LocalTime.of(9, 15));
        assertThat(d.tradeWindow().end()).isEqualTo(LocalTime.of(15, 10));
        assertThat(d.forceExitTime()).isEqualTo(LocalTime.of(15, 10));
        assertThat(d.maxTradesPerDay()).isEqualTo(1);
        assertThat(d.positionSizing().riskRupees()).isNull();
        assertThat(d.eventRules().action()).isEqualTo(EventAction.ALLOW);
        assertThat(d.regimePreferences()).isEmpty();
    }

    @Test
    void positionSizingAndTrailing() {
        StrategyDefinition d = parseValid(PRD_EXAMPLE + """
                position_sizing:
                  type: risk_based
                  risk_rupees: 2000
                trailing_stop:
                  type: breakeven_at_r
                  value: 1
                family: index
                """);
        assertThat(d.positionSizing().riskRupees().paise()).isEqualTo(200000L);
        assertThat(d.trailingStop().type()).isEqualTo(StrategyDefinition.TrailingType.BREAKEVEN_AT_R);
        assertThat(d.family()).isEqualTo(StrategyFamily.INDEX);
        assertThat(errorsOf(PRD_EXAMPLE + "position_sizing:\n  risk_rupees: 2000\n  risk_percent_of_capital: 1\n"))
                .extracting(ValidationError::path).contains("position_sizing");
    }
}
