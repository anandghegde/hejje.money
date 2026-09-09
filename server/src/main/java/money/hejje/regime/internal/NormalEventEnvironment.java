package money.hejje.regime.internal;

import java.time.LocalDate;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.EventEnvironmentSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code NORMAL} for every session until the events module (M3.3) provides a real source. */
@Configuration
class NormalEventEnvironment {

    @Bean
    @ConditionalOnMissingBean(EventEnvironmentSource.class)
    EventEnvironmentSource defaultEventEnvironment() {
        return (LocalDate session) -> EventEnvironment.NORMAL;
    }
}
