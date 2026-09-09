package money.hejje.strategy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyProperties;
import money.hejje.strategy.ValidationError;
import org.junit.jupiter.api.Test;

/** Every bundled definition in the repository's strategies/ directory must parse and validate. */
class BundledStrategiesTest {

    static final Path DIR = Path.of("../strategies");

    @Test
    void allBundledStrategiesValidate() throws Exception {
        DefinitionParser parser = new DefinitionParser();
        StrategyValidator validator = new StrategyValidator(new StrategyProperties(true, List.of(),
                Map.of("NIFTY", "nearest_future: NIFTY", "BANKNIFTY", "nearest_future: BANKNIFTY", "FINNIFTY", "nearest_future: FINNIFTY")));
        List<Path> files;
        try (Stream<Path> list = Files.list(DIR)) {
            files = list.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly("ema_pullback.yaml", "nifty_orb.yaml",
                "nifty_orb_breakdown.yaml", "pdh_pdl_breakout.yaml", "vwap_reversion.yaml", "vwap_trend_continuation.yaml");
        for (Path file : files) {
            StrategyDefinition def = parser.parse(Files.readString(file));
            List<ValidationError> errors = validator.validate(def);
            assertThat(errors).as(file.getFileName().toString()).isEmpty();
            assertThat(file.getFileName().toString()).isEqualTo(def.name() + ".yaml");
            assertThat(def.positionSizing().riskRupees()).as(file + " sizes by rupees at risk").isNotNull();
            assertThat(def.forceExitTime()).isEqualTo(java.time.LocalTime.of(15, 10));
        }
    }
}
