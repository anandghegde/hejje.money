package money.hejje.common.time;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import money.hejje.common.Exchange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Holiday calendar loaded from {@code exchange_holiday}. Loaded once at startup; call {@link #reload()} after edits. */
@Component
public class JdbcHolidayCalendar implements HolidayCalendar {

    private record Key(LocalDate date, Exchange exchange) {}

    private final JdbcClient jdbc;
    private volatile Set<Key> holidays = Set.of();

    public JdbcHolidayCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
        reload();
    }

    public final void reload() {
        Set<Key> loaded = new HashSet<>();
        jdbc.sql("SELECT date, exchange FROM exchange_holiday")
                .query((rs, i) -> new Key(rs.getObject("date", LocalDate.class), Exchange.valueOf(rs.getString("exchange"))))
                .list()
                .forEach(loaded::add);
        holidays = Set.copyOf(loaded);
    }

    @Override
    public boolean isHoliday(LocalDate date, Exchange exchange) {
        return holidays.contains(new Key(date, exchange));
    }
}
