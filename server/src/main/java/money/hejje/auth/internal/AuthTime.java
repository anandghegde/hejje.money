package money.hejje.auth.internal;

import java.time.Clock;
import java.time.Instant;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import org.springframework.stereotype.Component;

/**
 * The time authentication runs on: the application clock, except in SIM (plan M7.3), where tokens, API keys and refresh
 * sessions live in wall time. The simulation clock jumps hours in seconds, which would expire a 15-minute access token in
 * the middle of a replay.
 */
@Component
class AuthTime {

    private final Clock clock;

    AuthTime(Clock clock, HejjeProperties properties) {
        this.clock = properties.mode() == ExecutionMode.SIM ? Clock.systemUTC() : clock;
    }

    Clock clock() {
        return clock;
    }

    Instant now() {
        return clock.instant();
    }
}
