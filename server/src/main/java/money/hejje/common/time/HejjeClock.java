package money.hejje.common.time;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import money.hejje.common.Exchange;

/**
 * The only clock business logic may use. Wraps {@link java.time.Clock} and adds IST session helpers.
 * NSE regular session: 09:15 to 15:30 IST on weekdays that are not exchange holidays.
 */
public final class HejjeClock {

    public static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);
    public static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);

    private final Clock clock;
    private final ZoneId zone;
    private final HolidayCalendar holidays;

    public HejjeClock(Clock clock, ZoneId zone, HolidayCalendar holidays) {
        this.clock = clock;
        this.zone = zone;
        this.holidays = holidays;
    }

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return zone;
    }

    public ZonedDateTime nowIst() {
        return clock.instant().atZone(zone);
    }

    public LocalDate today() {
        return nowIst().toLocalDate();
    }

    public boolean isTradingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.isHoliday(date, Exchange.NSE);
    }

    /** Next trading day strictly after {@code date}. */
    public LocalDate nextTradingDay(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (!isTradingDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    public SessionWindow sessionWindow(LocalDate date) {
        return new SessionWindow(date.atTime(SESSION_OPEN).atZone(zone), date.atTime(SESSION_CLOSE).atZone(zone));
    }

    public boolean isSessionOpen() {
        LocalDate today = today();
        return isTradingDay(today) && sessionWindow(today).contains(now());
    }

    /** Whole minutes until today's session close, or 0 when the session is not open. */
    public long minutesToClose() {
        if (!isSessionOpen()) {
            return 0;
        }
        return Duration.between(now(), sessionWindow(today()).close().toInstant()).toMinutes();
    }
}
