package money.hejje.swing.internal;

import java.math.BigDecimal;
import money.hejje.common.ExecutionMode;
import money.hejje.signals.DeliverySizer;
import money.hejje.swing.SwingService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Swing signals are sized from the swing book's gap-adjusted risk budget (plan M11.3/M11.4). */
@Component
class SwingSizer implements DeliverySizer {

    private final SwingService swing;

    SwingSizer(@Lazy SwingService swing) {
        this.swing = swing;
    }

    @Override
    public int quantity(ExecutionMode mode, BigDecimal entry, BigDecimal stop) {
        return swing.size(mode, entry, stop).quantity();
    }
}
