package money.hejje.common.time;

import java.time.Clock;
import money.hejje.common.config.HejjeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /**
     * The system clock, or in SIM mode (plan M7.1) a {@link SimClock} starting at {@code hejje.sim.start} (default: now)
     * that only the replay moves.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock systemClock(HejjeProperties properties, org.springframework.core.env.Environment environment) {
        if (properties.mode() == money.hejje.common.ExecutionMode.SIM) {
            String start = environment.getProperty("hejje.sim.start");
            return new SimClock(start == null || start.isBlank() ? java.time.Instant.now() : java.time.Instant.parse(start), properties.timezone());
        }
        return Clock.systemUTC();
    }

    @Bean
    HejjeClock hejjeClock(Clock clock, HejjeProperties properties, HolidayCalendar holidays) {
        return new HejjeClock(clock, properties.timezone(), holidays);
    }
}
