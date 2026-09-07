package money.hejje.system.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import org.junit.jupiter.api.Test;

class ClockDriftCheckerTest {

    final MutableClock local = MutableClock.at("2026-09-08T04:00:00Z");
    final HejjeClock clock = new HejjeClock(local, MutableClock.IST, (d, e) -> false);

    ClockDriftChecker checker(boolean enabled, Instant remote) {
        return new ClockDriftChecker(enabled, () -> Optional.ofNullable(remote), Duration.ofSeconds(2), clock, new SimpleMeterRegistry());
    }

    @Test
    void healthyWithinLimit() {
        ClockDriftChecker c = checker(true, Instant.parse("2026-09-08T03:59:58.500Z"));
        assertThat(c.check().status()).isEqualTo(ClockDriftChecker.Status.HEALTHY);
        assertThat(c.lastResult().drift()).isEqualTo(Duration.ofMillis(1500));
        assertThat(c.result().allowsExecution()).isTrue();
    }

    @Test
    void degradedBeyondTwoSeconds() {
        ClockDriftChecker c = checker(true, Instant.parse("2026-09-08T04:00:03Z"));
        assertThat(c.check().status()).isEqualTo(ClockDriftChecker.Status.DEGRADED);
        assertThat(c.lastResult().drift()).isEqualTo(Duration.ofSeconds(-3));
        assertThat(c.result().allowsExecution()).isFalse();
        assertThat(c.name()).isEqualTo("clockSync");
    }

    @Test
    void unreachableReferenceIsUnknownButNotBlocking() {
        ClockDriftChecker c = checker(true, null);
        assertThat(c.check().status()).isEqualTo(ClockDriftChecker.Status.UNKNOWN);
        assertThat(c.result().allowsExecution()).isTrue();
    }

    @Test
    void disabledIsSkipped() {
        ClockDriftChecker c = checker(false, Instant.parse("2026-09-08T05:00:00Z"));
        assertThat(c.check().status()).isEqualTo(ClockDriftChecker.Status.SKIPPED);
        assertThat(c.result().allowsExecution()).isTrue();
    }
}
