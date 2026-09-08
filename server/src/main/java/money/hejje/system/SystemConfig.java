package money.hejje.system;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is disabled under the {@code test} profile so background jobs never race integration tests. */
@Configuration
@EnableScheduling
@Profile("!test")
class SystemConfig {
}
