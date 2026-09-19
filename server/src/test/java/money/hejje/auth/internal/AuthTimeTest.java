package money.hejje.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.SimClock;
import org.junit.jupiter.api.Test;

class AuthTimeTest {

    static HejjeProperties props(ExecutionMode mode) {
        return new HejjeProperties(mode, ZoneId.of("Asia/Kolkata"), Path.of("./data"));
    }

    @Test
    void simAuthenticatesOnWallTimeNotTheReplayClock() {
        SimClock sim = new SimClock(Instant.parse("2026-09-09T03:45:00Z"), ZoneId.of("Asia/Kolkata"));
        AuthTime time = new AuthTime(sim, props(ExecutionMode.SIM));
        sim.advance(Duration.ofHours(6)); // a replay jumps hours in seconds
        assertThat(Duration.between(Instant.now(), time.now()).abs()).isLessThan(Duration.ofSeconds(5));

        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        assertThat(new AuthTime(fixed, props(ExecutionMode.PAPER)).now()).as("other modes keep the application clock").isEqualTo(fixed.instant());
    }
}
