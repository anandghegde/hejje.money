package money.hejje.sim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.SimClock;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Simulation time of a SIM instance (plan M7.1): the {@link SimClock} and the {@link SimScheduler} built over every
 * {@code @Scheduled} method once all beans exist. The replay (M7.2) moves time only through here, so the clock and the
 * jobs never disagree.
 */
@Component
@ConditionalOnProperty(name = "hejje.mode", havingValue = "SIM")
public class SimTime implements SmartInitializingSingleton {

    private final SimClock clock;
    private final ListableBeanFactory beans;
    private final Environment environment;
    private final HejjeProperties properties;
    private volatile SimScheduler scheduler;

    SimTime(Clock clock, ListableBeanFactory beans, Environment environment, HejjeProperties properties) {
        if (!(clock instanceof SimClock sim)) {
            throw new IllegalStateException("hejje.mode=SIM needs the simulation clock, found " + clock.getClass().getName());
        }
        this.clock = sim;
        this.beans = beans;
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        scheduler = new SimScheduler(clock, SimJobs.standard(), SimScheduler.discover(beans, environment, properties.timezone()));
    }

    public SimClock clock() {
        return clock;
    }

    public SimScheduler scheduler() {
        return scheduler;
    }

    /** Starts simulation time at {@code at} (a new session) and reschedules every job from there. */
    public synchronized void startAt(Instant at) {
        clock.set(at);
        scheduler.restart();
    }

    /** One replay step: moves the clock and fires the jobs now due. */
    public synchronized List<String> advance(Duration step) {
        return scheduler.advance(step);
    }
}
