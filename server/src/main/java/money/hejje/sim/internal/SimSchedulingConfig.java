package money.hejje.sim.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * In SIM no {@code @Scheduled} job may run on wall time: Spring's task scheduler is replaced by one that drops every
 * task, and {@link money.hejje.sim.SimScheduler} fires them on simulation time instead (plan M7.1).
 */
@Configuration
@ConditionalOnProperty(name = "hejje.mode", havingValue = "SIM")
class SimSchedulingConfig {

    @Bean("taskScheduler")
    @Primary
    TaskScheduler simTaskScheduler() {
        return new DroppingTaskScheduler();
    }

    /** Accepts every task and runs none. */
    static final class DroppingTaskScheduler implements TaskScheduler {
        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return null;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return null;
        }
    }
}
