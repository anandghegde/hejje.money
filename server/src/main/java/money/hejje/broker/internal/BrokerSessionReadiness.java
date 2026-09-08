package money.hejje.broker.internal;

import money.hejje.broker.BrokerSessionService;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.BrokerSessionStatus;
import money.hejje.system.ReadinessCheck;
import org.springframework.stereotype.Component;

/** Readiness line {@code brokerSession}: BLOCKING unless the broker session is CONNECTED. */
@Component
class BrokerSessionReadiness implements ReadinessCheck {

    private final BrokerSessionService sessions;

    BrokerSessionReadiness(BrokerSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public String name() {
        return "brokerSession";
    }

    @Override
    public CheckResult result() {
        BrokerSessionStatus status = sessions.status();
        String detail = status.broker() + " " + status.state() + (status.detail() != null ? " (" + status.detail() + ")" : "");
        return status.state() == BrokerSessionState.CONNECTED ? CheckResult.ok(detail) : CheckResult.blocking(detail);
    }
}
