package money.hejje.strategy;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strategy module settings.
 *
 * @param loadBundled  load {@code strategies/*.yaml} at startup (filesystem dirs first, then the classpath copy)
 * @param bundledDirs  directories searched for bundled definitions; the first that exists wins
 * @param aliases      bare universe names to canonical symbols or selectors, for example {@code NIFTY -> nearest_future: NIFTY}
 */
@ConfigurationProperties("hejje.strategy")
public record StrategyProperties(
        @DefaultValue("true") boolean loadBundled,
        @DefaultValue({"./strategies", "../strategies"}) List<String> bundledDirs,
        @DefaultValue({}) Map<String, String> aliases) {
}
