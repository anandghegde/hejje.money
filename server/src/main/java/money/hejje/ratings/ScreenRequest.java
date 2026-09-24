package money.hejje.ratings;

import java.time.LocalDate;
import java.util.List;

/**
 * A screener request, also the stored definition of a saved screen (without the date).
 *
 * @param filters all must hold (AND)
 * @param sort    a field name, descending when prefixed with {@code -}; default {@code -techComposite}
 * @param limit   1..1000, default 50
 */
public record ScreenRequest(LocalDate date, List<Filter> filters, String sort, Integer limit) {

    /**
     * @param op    {@code gte, lte, gt, lt, eq, ne, in}
     * @param value a number, a string, or a list for {@code in}
     */
    public record Filter(String field, String op, Object value) {
    }
}
