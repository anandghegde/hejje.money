package money.hejje.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SimClockTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void standsStillUntilSetOrAdvancedAndRefusesToMoveWhilePaused() throws Exception {
        Instant open = Instant.parse("2026-09-08T03:45:00Z"); // 09:15 IST
        SimClock clock = new SimClock(open, IST);
        Thread.sleep(5);
        assertThat(clock.instant()).isEqualTo(open);
        assertThat(clock.advance(Duration.ofMinutes(1))).isEqualTo(open.plusSeconds(60));

        clock.pause();
        assertThat(clock.paused()).isTrue();
        assertThatThrownBy(() -> clock.advance(Duration.ofMinutes(1))).isInstanceOf(IllegalStateException.class);
        assertThat(clock.instant()).isEqualTo(open.plusSeconds(60));
        clock.resume();
        assertThatThrownBy(() -> clock.advance(Duration.ofMinutes(-1))).isInstanceOf(IllegalArgumentException.class);

        // a new session may start earlier than the last one ended
        clock.set(open.minus(Duration.ofDays(1)));
        assertThat(clock.instant()).isEqualTo(open.minus(Duration.ofDays(1)));
        assertThat(clock.getZone()).isEqualTo(IST);
        assertThat(clock.withZone(ZoneId.of("UTC")).instant()).isEqualTo(clock.instant());
    }

    @Test
    void hejjeClockReadsSimulationTime() {
        SimClock clock = new SimClock(Instant.parse("2026-09-08T09:40:00Z"), IST); // 15:10 IST
        HejjeClock hejje = new HejjeClock(clock, IST, (d, e) -> false);
        assertThat(hejje.nowIst().toLocalTime()).hasToString("15:10");
        assertThat(hejje.isSessionOpen()).isTrue();
        clock.advance(Duration.ofMinutes(21));
        assertThat(hejje.isSessionOpen()).isFalse();
    }
}
