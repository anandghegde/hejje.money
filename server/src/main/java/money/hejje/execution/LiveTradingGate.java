package money.hejje.execution;

import java.util.ArrayList;
import java.util.List;
import money.hejje.broker.BrokerSessionService;
import money.hejje.common.config.HejjeProperties;
import money.hejje.orders.OrderIntent;
import money.hejje.risk.RiskService;
import money.hejje.system.ExecutionReadiness;
import org.springframework.stereotype.Component;

/**
 * The single place the pipeline asks "may this order go to the broker now?" (PRD sections 32, 41, 42, 63). Composes the
 * server mode, kill switch, broker session and every readiness check (lease, clock, egress IP, broker, reconciliation,
 * market-data staleness, bootstrap). Reasons are returned to the client. Exposure-reducing intents bypass the kill switch.
 */
@Component
public class LiveTradingGate {

    private final ExecutionReadiness readiness;
    private final BrokerSessionService broker;
    private final RiskService risk;
    private final HejjeProperties properties;

    LiveTradingGate(ExecutionReadiness readiness, BrokerSessionService broker, RiskService risk, HejjeProperties properties) {
        this.readiness = readiness;
        this.broker = broker;
        this.risk = risk;
        this.properties = properties;
    }

    /** The reasons this intent may not proceed. Empty means the gate is open. */
    public List<String> check(OrderIntent intent) {
        List<String> reasons = new ArrayList<>();
        if (!broker.isConnected()) {
            reasons.add("Broker is not connected");
        }
        if (!readiness.isExecutionEnabled()) {
            reasons.addAll(readiness.reasons());
        }
        if (!intent.isExposureReducing() && risk.killSwitch(properties.mode()).stopNewOrders()) {
            reasons.add("Kill switch is stopping new orders");
        }
        return reasons;
    }
}
