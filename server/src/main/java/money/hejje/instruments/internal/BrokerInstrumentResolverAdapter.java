package money.hejje.instruments.internal;

import java.util.Optional;
import java.util.UUID;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.instruments.Instrument;
import org.springframework.stereotype.Component;

/** Implements the broker module's lookup port on top of the instrument master. */
@Component
class BrokerInstrumentResolverAdapter implements BrokerInstrumentResolver {

    private final InstrumentStore store;

    BrokerInstrumentResolverAdapter(InstrumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<BrokerInstrumentRef> forInstrument(UUID instrumentId, String broker) {
        return store.findById(instrumentId).flatMap(i -> store.findMapping(instrumentId, broker)
                .map(m -> new BrokerInstrumentRef(i.id(), i.exchange(), i.type(), m.brokerToken(), m.tradingSymbol(), m.exchangeSegment(),
                        i.lotSize(), i.tickSize())));
    }

    @Override
    public Optional<UUID> byBrokerToken(String broker, String brokerToken) {
        return store.findByBrokerToken(broker, brokerToken).map(Instrument::id);
    }

    @Override
    public Optional<UUID> byTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
        return store.findByTradingSymbol(broker, exchangeSegment, tradingSymbol).map(Instrument::id);
    }
}
