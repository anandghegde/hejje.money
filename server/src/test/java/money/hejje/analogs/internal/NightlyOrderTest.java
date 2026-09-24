package money.hejje.analogs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import money.hejje.ratings.DailyCandlesRefreshed;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

/**
 * The evening chain is ratings → bases → alerts, then analogs → digest: the digest reads the day's base transitions.
 * Both listen to {@link DailyCandlesRefreshed}; Spring runs the lower {@code @Order} value first, and a listener
 * without one has the lowest precedence, so both must carry an explicit order.
 */
class NightlyOrderTest {

    private static int orderOf(String type) throws Exception {
        Method on = Class.forName(type).getDeclaredMethod("on", DailyCandlesRefreshed.class);
        Order order = on.getAnnotation(Order.class);
        assertThat(order).as("%s.on needs an explicit @Order", type).isNotNull();
        return order.value();
    }

    @Test
    void theRatingsRunBeforeTheAnalogs() throws Exception {
        assertThat(orderOf("money.hejje.ratings.internal.NightlyContext")).isLessThan(orderOf("money.hejje.analogs.internal.AnalogsNightly"));
    }
}
