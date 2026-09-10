package money.hejje.execution.internal;

import java.util.Optional;
import money.hejje.broker.BrokerAccount;
import money.hejje.broker.BrokerAccountService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerSessionService;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.BrokerSessionStatus;
import money.hejje.system.ReadinessCheck;
import org.springframework.stereotype.Component;

/** Orders go only to the active transactional broker account (plan M5.6). */
@Component
class BrokerAccountReadiness implements ReadinessCheck {

    private final BrokerAccountService accounts;
    private final BrokerSessionService sessions;
    private final BrokerAdapter broker;

    BrokerAccountReadiness(BrokerAccountService accounts, BrokerSessionService sessions, BrokerAdapter broker) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.broker = broker;
    }

    @Override
    public String name() {
        return "brokerAccount";
    }

    @Override
    public CheckResult result() {
        String code = broker.instrumentBrokerCode();
        if ("fake".equals(code)) {
            return CheckResult.skipped("fake broker");
        }
        BrokerSessionStatus status = sessions.status();
        if (status.state() != BrokerSessionState.CONNECTED || status.brokerUserId() == null) {
            return CheckResult.skipped("no broker session");
        }
        Optional<BrokerAccount> active = accounts.active();
        if (active.isEmpty()) {
            return CheckResult.ok("no broker account registered yet; the next login registers " + code + "/" + status.brokerUserId());
        }
        BrokerAccount a = active.get();
        if (a.broker().equals(code) && a.accountId().equals(status.brokerUserId())) {
            return CheckResult.ok("active account " + code + "/" + a.accountId());
        }
        return CheckResult.blocking("connected account " + code + "/" + status.brokerUserId() + " is not the active transactional account "
                + a.broker() + "/" + a.accountId());
    }
}
