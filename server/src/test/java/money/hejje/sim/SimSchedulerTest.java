package money.hejje.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import money.hejje.common.time.SimClock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;

class SimSchedulerTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Instant OPEN = Instant.parse("2026-09-08T03:45:00Z"); // 09:15 IST

    /** Every run appends "<job>@<simulated IST time>". */
    static final List<String> RUNS = new ArrayList<>();

    static class Jobs {
        final SimClock clock;

        Jobs(SimClock clock) {
            this.clock = clock;
        }

        private void log(String what) {
            RUNS.add(what + "@" + clock.instant().atZone(IST).toLocalTime());
        }

        @Scheduled(fixedRate = 1000)
        void everySecond() {
            log("rate");
        }

        @Scheduled(fixedDelayString = "${sim.test.delay:PT5M}", initialDelayString = "PT1M")
        void everyFive() {
            log("delay");
        }

        @Scheduled(cron = "0 10 15 * * MON-FRI", zone = "Asia/Kolkata")
        void forceExitTime() {
            log("cron");
        }

        @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
        void skipped() {
            log("skipped");
        }
    }

    static SimJobs registry(String... runKeysInOrder) {
        Map<String, SimJobs.Entry> m = new LinkedHashMap<>();
        for (String k : runKeysInOrder) {
            m.put(k, new SimJobs.Entry(SimJobs.Policy.RUN, "test"));
        }
        m.put("Jobs#skipped", new SimJobs.Entry(SimJobs.Policy.SKIP, "test"));
        return new SimJobs(m);
    }

    static SimScheduler scheduler(SimClock clock, SimJobs registry, MockEnvironment env) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(SimClock.class, () -> clock);
            ctx.registerBean(Jobs.class);
            ctx.refresh();
            return new SimScheduler(clock, registry, SimScheduler.discover(ctx, env, IST));
        }
    }

    @Test
    void jobsFireOnSimulationTimeInRegistryOrderAndCoalesceWithinAStep() {
        RUNS.clear();
        SimClock clock = new SimClock(OPEN, IST);
        SimScheduler s = scheduler(clock, registry("Jobs#forceExitTime", "Jobs#everyFive", "Jobs#everySecond"), new MockEnvironment());
        assertThat(s.runningJobs()).containsExactly("Jobs#forceExitTime", "Jobs#everyFive", "Jobs#everySecond");
        assertThat(RUNS).as("nothing runs on wall time").isEmpty();

        // the fixed-rate job is due at the start; the fixed-delay job after its initial delay
        assertThat(s.runDue()).containsExactly("Jobs#everySecond");
        // one minute = sixty seconds of the fixed-rate job, coalesced into one firing
        assertThat(s.advance(Duration.ofMinutes(1))).containsExactly("Jobs#everyFive", "Jobs#everySecond");
        assertThat(RUNS).containsExactly("rate@09:15", "delay@09:16", "rate@09:16");
        assertThat(s.advance(Duration.ofMinutes(4))).containsExactly("Jobs#everySecond");
        assertThat(s.advance(Duration.ofMinutes(1))).containsExactly("Jobs#everyFive", "Jobs#everySecond"); // 09:21: 5 minutes after 09:16
        assertThat(s.nextFirings().get("Jobs#everyFive")).isEqualTo(OPEN.plus(Duration.ofMinutes(11)));

        // the cron job fires when simulated time reaches 15:10, however large the step
        RUNS.clear();
        List<String> ran = s.advance(Duration.between(clock.instant(), OPEN.plus(Duration.ofMinutes(355))));
        assertThat(ran).first().isEqualTo("Jobs#forceExitTime");
        assertThat(RUNS.get(0)).isEqualTo("cron@15:10");
        assertThat(s.firedCounts()).doesNotContainKey("Jobs#skipped").containsEntry("Jobs#forceExitTime", 1);
        assertThat(s.nextFirings().get("Jobs#forceExitTime")).isEqualTo(Instant.parse("2026-09-09T09:40:00Z"));
    }

    @Test
    void placeholdersResolveAndARestartReschedulesFromTheNewTime() {
        RUNS.clear();
        SimClock clock = new SimClock(OPEN, IST);
        SimScheduler s = scheduler(clock, registry("Jobs#everyFive", "Jobs#everySecond", "Jobs#forceExitTime"),
                new MockEnvironment().withProperty("sim.test.delay", "2m"));
        s.advance(Duration.ofMinutes(1));
        s.advance(Duration.ofMinutes(2));
        assertThat(RUNS).filteredOn(r -> r.startsWith("delay")).containsExactly("delay@09:16", "delay@09:18");

        clock.set(OPEN.minus(Duration.ofDays(1)));
        s.restart();
        assertThat(s.nextFirings().get("Jobs#everyFive")).isEqualTo(OPEN.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(1)));
    }

    @Test
    void aScheduledMethodMissingFromTheRegistryIsRefused() {
        SimClock clock = new SimClock(OPEN, IST);
        assertThatThrownBy(() -> scheduler(clock, registry("Jobs#everySecond"), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Jobs#everyFive").hasMessageContaining("Jobs#forceExitTime");
    }

    @Test
    void durationsParseLikeSpring() {
        assertThat(SimScheduler.parse("PT5M", TimeUnit.MILLISECONDS)).isEqualTo(Duration.ofMinutes(5));
        assertThat(SimScheduler.parse("30s", TimeUnit.MILLISECONDS)).isEqualTo(Duration.ofSeconds(30));
        assertThat(SimScheduler.parse("5m", TimeUnit.MILLISECONDS)).isEqualTo(Duration.ofMinutes(5));
        assertThat(SimScheduler.parse("1500", TimeUnit.MILLISECONDS)).isEqualTo(Duration.ofMillis(1500));
        assertThat(SimScheduler.parse("2", TimeUnit.MINUTES)).isEqualTo(Duration.ofMinutes(2));
    }
}
