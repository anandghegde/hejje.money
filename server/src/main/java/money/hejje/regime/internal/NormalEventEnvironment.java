package money.hejje.regime.internal;

import java.time.LocalDate;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.EventEnvironmentSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code NORMAL} for every session; the events module's {@code @Primary} source (M3.3) takes over when present. */
@Configuration
class NormalEventEnvironment {

    @Bean
    EventEnvironmentSource defaultEventEnvironment() {
        return (LocalDate session) -> EventEnvironment.NORMAL;
    }
}
