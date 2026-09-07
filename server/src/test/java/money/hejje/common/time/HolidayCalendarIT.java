package money.hejje.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import money.hejje.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HolidayCalendarIT extends AbstractIntegrationTest {

    @Autowired
    HejjeClock clock;

    @Test
    void seededNseHolidaysAreNotTradingDays() {
        assertThat(clock.isTradingDay(LocalDate.of(2026, 1, 26))).isFalse(); // Republic Day
        assertThat(clock.isTradingDay(LocalDate.of(2026, 11, 10))).isFalse(); // Diwali-Balipratipada
        assertThat(clock.isTradingDay(LocalDate.of(2026, 1, 27))).isTrue();
        assertThat(clock.zone().getId()).isEqualTo("Asia/Kolkata");
    }
}
