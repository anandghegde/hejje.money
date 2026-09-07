package money.hejje.common.time;

import java.time.LocalDate;
import money.hejje.common.Exchange;

/** Source of exchange holidays. Backed by the {@code exchange_holiday} table in production. */
@FunctionalInterface
public interface HolidayCalendar {

    boolean isHoliday(LocalDate date, Exchange exchange);
}
