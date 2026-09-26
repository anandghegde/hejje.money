package money.hejje.swing.internal;

import money.hejje.common.ExecutionMode;
import money.hejje.risk.OvernightRiskSource;
import money.hejje.swing.SwingService;
import org.springframework.stereotype.Component;

/** The swing book's gap-adjusted overnight risk on the risk dashboard (plan M11.3). */
@Component
class SwingOvernightRiskSource implements OvernightRiskSource {

    private final SwingService swing;

    SwingOvernightRiskSource(SwingService swing) {
        this.swing = swing;
    }

    @Override
    public OvernightRisk overnightRisk(ExecutionMode mode) {
        SwingService.OvernightRisk r = swing.overnightRisk(mode);
        return new OvernightRisk(r.overnightRisk(), r.budget(), r.openPositions());
    }
}
