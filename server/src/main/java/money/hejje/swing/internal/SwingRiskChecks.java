package money.hejje.swing.internal;

import java.util.List;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.orders.OrderIntent;
import money.hejje.risk.RiskCheck;
import money.hejje.risk.RiskCheckContributor;
import money.hejje.swing.SwingService;
import org.springframework.stereotype.Component;

/**
 * The swing book's pre-trade controls on new delivery (CNC) exposure (plans M11.2, M11.3): PAPER only, a stop, every open
 * position protected by its GTT, open positions, gap-adjusted risk per position and overnight, capital, industry
 * concentration, the session before a blocking event, and surveillance. Each failure names its limit. Long only: a
 * delivery sell that is not closing a position is refused.
 */
@Component
class SwingRiskChecks implements RiskCheckContributor {

    private final SwingService swing;

    SwingRiskChecks(SwingService swing) {
        this.swing = swing;
    }

    @Override
    public List<RiskCheck> contribute(OrderIntent intent) {
        if (intent.product() != Product.CNC) {
            return List.of();
        }
        if (intent.side() == Side.SELL) {
            // exposure-reducing sells never reach the contributors: this one would open a short
            return List.of(RiskCheck.fail("swingLongOnly", "SELL without a delivery position", "long only",
                    "delivery positions are long only: there are no overnight shorts in the cash market"));
        }
        return swing.checks(intent);
    }
}
