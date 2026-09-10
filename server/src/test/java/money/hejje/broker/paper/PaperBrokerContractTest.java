package money.hejje.broker.paper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerAdapterContractTest;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.Quote;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;

/** Paper wraps a real broker for data and simulates orders; its delegate here is a stand-in that is never sent an order. */
class PaperBrokerContractTest extends BrokerAdapterContractTest {

    static final BrokerInstrumentResolver RESOLVER = new BrokerInstrumentResolver() {
        @Override
        public Optional<BrokerInstrumentRef> forInstrument(UUID instrumentId, String broker) {
            return INSTRUMENT.equals(instrumentId)
                    ? Optional.of(new BrokerInstrumentRef(INSTRUMENT, Exchange.NSE, InstrumentType.EQ, "408065", "INFY", "NSE", 1, new BigDecimal("0.05")))
                    : Optional.empty();
        }

        @Override
        public Optional<UUID> byBrokerToken(String broker, String brokerToken) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> byTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
            return Optional.empty();
        }
    };

    BrokerAdapter delegate;
    PaperBrokerAdapter paper;

    @Override
    protected BrokerAdapter connect() {
        delegate = mock(BrokerAdapter.class);
        AtomicReference<BrokerSessionState> state = new AtomicReference<>(BrokerSessionState.CONNECTED);
        when(delegate.brokerCode()).thenReturn("zerodha");
        when(delegate.instrumentBrokerCode()).thenReturn("zerodha");
        when(delegate.sessionState()).thenAnswer(i -> state.get());
        doAnswer(i -> {
            state.set(BrokerSessionState.DISCONNECTED);
            return null;
        }).when(delegate).clearSession();
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        paper = new PaperBrokerAdapter(delegate, RESOLVER, new BrokerOrderUpdates(), clock, new PaperBrokerProperties(new BigDecimal("5"), 0.0, 1_000_000));
        return paper;
    }

    @Override
    protected void givenQuote(String lastPrice) {
        BigDecimal price = new BigDecimal(lastPrice);
        paper.injectTick(new MarketTick(INSTRUMENT, Instant.now(), price, null, null, 0, 0, MarketTick.Mode.FULL));
        when(delegate.getQuote(any())).thenReturn(List.of(new Quote(INSTRUMENT, Instant.now(), price, null, null, 0, 0, null, null, null, null)));
    }

    /** Paper never calls the broker for orders, so there are no broker errors to map. */
    @Override
    protected Set<BrokerException.Kind> scriptedErrors() {
        return Set.of();
    }

    @Override
    protected void assertNoOrderSent() {
        verify(delegate, never()).placeOrder(any());
    }
}
