package money.hejje.common.time;

import java.time.Clock;
import money.hejje.common.config.HejjeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    HejjeClock hejjeClock(Clock clock, HejjeProperties properties, HolidayCalendar holidays) {
        return new HejjeClock(clock, properties.timezone(), holidays);
    }
}
