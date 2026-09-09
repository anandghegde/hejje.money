package money.hejje.risk;

import java.util.List;
import money.hejje.orders.OrderIntent;

/**
 * Extension point for controls owned by other modules (for example strategy event rules, plan M3.3). Contributions
 * are evaluated only for intents that add exposure, after the built-in controls; closing orders are never blocked by
 * a contributor. A contributor must return an empty list when the intent is none of its business and must never throw.
 */
public interface RiskCheckContributor {

    List<RiskCheck> contribute(OrderIntent intent);
}
