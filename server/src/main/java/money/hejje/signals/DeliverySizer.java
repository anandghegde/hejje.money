package money.hejje.signals;

import java.math.BigDecimal;
import money.hejje.common.ExecutionMode;

/**
 * Sizes a swing (delivery) entry from the swing book's gap-adjusted risk budget (plan M11.3/M11.4). Implemented by the
 * swing module, which depends on signals, so signals asks through this interface.
 */
public interface DeliverySizer {

    /** The largest quantity the swing limits allow at {@code entry} with {@code stop}; 0 when none. */
    int quantity(ExecutionMode mode, BigDecimal entry, BigDecimal stop);
}
