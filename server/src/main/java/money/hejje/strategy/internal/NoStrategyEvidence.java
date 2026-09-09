package money.hejje.strategy.internal;

import java.util.UUID;
import money.hejje.strategy.StrategyEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default evidence port until the backtest module (M2.3) supplies the real one: nothing has been backtested. */
@Configuration
class NoStrategyEvidence {

    @Bean
    @ConditionalOnMissingBean(StrategyEvidence.class)
    StrategyEvidence noEvidence() {
        return new StrategyEvidence() {
            @Override
            public boolean hasBacktest(UUID versionId) {
                return false;
            }

            @Override
            public boolean hasValidatedBacktest(UUID versionId) {
                return false;
            }
        };
    }
}
