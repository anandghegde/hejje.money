package money.hejje.system;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is disabled under the {@code test} profile so background jobs never race integration tests, and in SIM mode,
 * where {@code money.hejje.sim.SimScheduler} runs the jobs on simulation time (plan M7.1).
 */
@Configuration
@EnableScheduling
@Profile("!test")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!'${hejje.mode:PAPER}'.equalsIgnoreCase('SIM')")
class SystemConfig {
}
