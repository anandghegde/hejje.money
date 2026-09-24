package money.hejje.ratings;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Further screener fields contributed by another module (the analogs module adds the daily-analog evidence). Values are
 * numbers or strings; a symbol without a value simply lacks the field that session.
 */
public interface ScreenFieldSource {

    /** The field names this source can supply, for validation and documentation. */
    List<String> fields();

    /** Values per symbol for the newest session of the source on or before {@code date}. */
    Map<String, Map<String, Object>> values(LocalDate date);
}
