package money.hejje.backtest.experiments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param parallelism variants backtested at once
 * @param maxVariants variants per experiment (the baseline not counted)
 */
@ConfigurationProperties("hejje.backtest.experiments")
public record ExperimentProperties(@DefaultValue("2") int parallelism, @DefaultValue("12") int maxVariants) {
}
