package money.hejje.events.internal;

import java.util.List;
import java.util.Optional;
import money.hejje.events.EventRuleOutcome;
import money.hejje.events.EventService;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderReason;
import money.hejje.risk.RiskCheck;
import money.hejje.risk.RiskCheckContributor;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.springframework.stereotype.Component;

/**
 * Risk pipeline control {@code eventRule}: a STRATEGY_SIGNAL intent whose strategy version says {@code action: block}
 * is rejected deterministically while a HIGH-risk event is within the rule's window, so a manual execute of a blocked
 * signal fails the same way the recommendation says AVOID. Caution only annotates.
 */
@Component
class EventRuleCheck implements RiskCheckContributor {

    static final String NAME = "eventRule";

    private final EventService events;
    private final org.springframework.beans.factory.ObjectProvider<SignalService> signals; // lazy: the signal engine's execution port builds the risk engine
    private final StrategyService strategies;

    EventRuleCheck(EventService events, org.springframework.beans.factory.ObjectProvider<SignalService> signals, StrategyService strategies) {
        this.events = events;
        this.signals = signals;
        this.strategies = strategies;
    }

    @Override
    public List<RiskCheck> contribute(OrderIntent intent) {
        if (intent.reason() != OrderReason.STRATEGY_SIGNAL || intent.signalId() == null) {
            return List.of();
        }
        Optional<Signal> signal = signals.getObject().find(intent.signalId());
        Optional<StrategyVersion> version = signal.flatMap(s -> strategies.versionById(s.versionId()));
        if (version.isEmpty()) {
            return List.of();
        }
        EventRuleOutcome outcome = events.rule(version.get().definition(), intent.instrumentId());
        if (outcome.blocks()) {
            return List.of(RiskCheck.fail(NAME, "HIGH", "block", outcome.reason()));
        }
        return List.of(RiskCheck.pass(NAME, outcome.reason()));
    }
}
