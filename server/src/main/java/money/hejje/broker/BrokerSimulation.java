package money.hejje.broker;

import money.hejje.broker.paper.PaperBrokerAdapter;
import money.hejje.common.Money;
import money.hejje.common.event.MarketTick;

/**
 * The simulated broker of a SIM instance (plan M7.2): the paper adapter over the fake broker, filling on replayed ticks.
 * The replay feeds it every tick, resets it per session and waits for its order updates to be delivered.
 */
public final class BrokerSimulation implements money.hejje.common.Drainable {

    private final PaperBrokerAdapter paper;

    public BrokerSimulation(PaperBrokerAdapter paper) {
        this.paper = paper;
    }

    /** A replayed tick: moves the last price and fills resting and pending orders it crosses. */
    public void onTick(MarketTick tick) {
        paper.injectTick(tick);
    }

    public void reset(Money capital) {
        paper.reset(capital);
    }

    /** Blocks until every order update published so far has been delivered to the execution module. */
    @Override
    public void drain() {
        paper.flush();
    }
}
