package money.hejje.risk.internal;

import java.util.List;
import money.hejje.orders.OrderIntent;
import money.hejje.risk.RiskCheck;
import money.hejje.risk.RiskDecision;
import money.hejje.risk.RiskEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** M1.4 stub: approves every intent. Replaced by the real engine in M1.5 (which registers its own {@link RiskEngine} bean). */
@Configuration
class RiskEngineStubConfig {

    @Bean
    @ConditionalOnMissingBean(RiskEngine.class)
    RiskEngine approveAllRiskEngine() {
        return intent -> RiskDecision.approved(List.of(RiskCheck.pass("stub", "risk engine not yet enabled (M1.5)")));
    }
}
