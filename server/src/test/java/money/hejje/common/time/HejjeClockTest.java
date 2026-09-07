package money.hejje.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import money.hejje.common.Exchange;
import org.junit.jupiter.api.Test;

class HejjeClockTest {

    static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2026, 9, 14)); // Ganesh Chaturthi, a Monday

    static HejjeClock clockAt(String ist) {
        return new HejjeClock(MutableClock.atIst(ist), MutableClock.IST,
                (date, exchange) -> exchange == Exchange.NSE && HOLIDAYS.contains(date));
    }

    @Test
    void weekendsAndHolidaysAreNotTradingDays() {
        HejjeClock clock = clockAt("2026-09-08T10:00:00");
        assertThat(clock.isTradingDay(LocalDate.of(2026, 9, 8))).isTrue();   // Tuesday
        assertThat(clock.isTradingDay(LocalDate.of(2026, 9, 12))).isFalse(); // Saturday
        assertThat(clock.isTradingDay(LocalDate.of(2026, 9, 13))).isFalse(); // Sunday
        assertThat(clock.isTradingDay(LocalDate.of(2026, 9, 14))).isFalse(); // holiday
        assertThat(clock.nextTradingDay(LocalDate.of(2026, 9, 11))).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void todayAndNowAreInIst() {
        HejjeClock clock = clockAt("2026-09-08T01:30:00"); // 2026-09-07T20:00Z
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(clock.nowIst().getHour()).isEqualTo(1);
        assertThat(clock.now().toString()).isEqualTo("2026-09-07T20:00:00Z");
    }

    @Test
    void sessionWindowIs0915To1530() {
        SessionWindow window = clockAt("2026-09-08T10:00:00").sessionWindow(LocalDate.of(2026, 9, 8));
        assertThat(window.open().toLocalTime()).isEqualTo(HejjeClock.SESSION_OPEN);
        assertThat(window.close().toLocalTime()).isEqualTo(HejjeClock.SESSION_CLOSE);
        assertThat(window.open().toInstant().toString()).isEqualTo("2026-09-08T03:45:00Z");
    }

    @Test
    void sessionEdges() {
        assertThat(clockAt("2026-09-08T09:14:59").isSessionOpen()).isFalse();
        assertThat(clockAt("2026-09-08T09:15:00").isSessionOpen()).isTrue();
        assertThat(clockAt("2026-09-08T15:29:59").isSessionOpen()).isTrue();
        assertThat(clockAt("2026-09-08T15:30:00").isSessionOpen()).isFalse();
        assertThat(clockAt("2026-09-12T10:00:00").isSessionOpen()).isFalse(); // Saturday
        assertThat(clockAt("2026-09-14T10:00:00").isSessionOpen()).isFalse(); // holiday
    }

    @Test
    void minutesToClose() {
        assertThat(clockAt("2026-09-08T15:00:00").minutesToClose()).isEqualTo(30);
        assertThat(clockAt("2026-09-08T15:29:30").minutesToClose()).isEqualTo(0);
        assertThat(clockAt("2026-09-08T16:00:00").minutesToClose()).isEqualTo(0);
        assertThat(clockAt("2026-09-13T12:00:00").minutesToClose()).isEqualTo(0);
    }
}
