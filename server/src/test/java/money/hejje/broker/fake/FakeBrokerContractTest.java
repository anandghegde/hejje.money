package money.hejje.broker.fake;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerAdapterContractTest;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;

class FakeBrokerContractTest extends BrokerAdapterContractTest {

    static final BrokerInstrumentResolver RESOLVER = new BrokerInstrumentResolver() {
        @Override
        public Optional<BrokerInstrumentRef> forInstrument(UUID instrumentId, String broker) {
            return INSTRUMENT.equals(instrumentId)
                    ? Optional.of(new BrokerInstrumentRef(INSTRUMENT, Exchange.NSE, InstrumentType.EQ, "408065", "INFY", "NSE", 1, new BigDecimal("0.05")))
                    : Optional.empty();
        }

        @Override
        public Optional<UUID> byBrokerToken(String broker, String brokerToken) {
            return "408065".equals(brokerToken) ? Optional.of(INSTRUMENT) : Optional.empty();
        }

        @Override
        public Optional<UUID> byTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
            return "INFY".equals(tradingSymbol) ? Optional.of(INSTRUMENT) : Optional.empty();
        }
    };

    FakeBrokerAdapter fake;

    @Override
    protected BrokerAdapter connect() {
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        fake = new FakeBrokerAdapter(new FakeBrokerProperties(true, 1_000_000), new BrokerOrderUpdates(), RESOLVER, clock, e -> { });
        return fake;
    }

    @Override
    protected void givenQuote(String lastPrice) {
        fake.injectQuote(INSTRUMENT, lastPrice);
    }

    @Override
    protected Set<BrokerException.Kind> scriptedErrors() {
        return EnumSet.of(BrokerException.Kind.AUTH, BrokerException.Kind.RATE_LIMIT, BrokerException.Kind.INPUT, BrokerException.Kind.REJECTED,
                BrokerException.Kind.NETWORK, BrokerException.Kind.TIMEOUT);
    }

    @Override
    protected void givenNextPlaceFails(BrokerException.Kind kind) {
        fake.failNext(kind);
    }

    @Override
    protected GttSupport gttSupport() {
        return GttSupport.SIMULATED;
    }

    @Override
    protected void assertNoOrderSent() {
        org.assertj.core.api.Assertions.assertThat(fake.getOrders()).isEmpty();
    }
}
