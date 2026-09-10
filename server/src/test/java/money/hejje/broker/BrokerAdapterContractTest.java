package money.hejje.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Validity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The broker adapter contract (plan M5.6): every adapter — fake, paper, Zerodha, Dhan — passes these, each against its
 * own harness (WireMock for the real brokers). A new adapter subclasses this in its package and wires the scenario
 * hooks to its broker's responses.
 */
public abstract class BrokerAdapterContractTest {

    /** The one mapped instrument of every harness (NSE INFY). */
    protected static final UUID INSTRUMENT = UUID.fromString("0192a000-0000-7000-8000-0000000000c1");
    protected static final String TAG = "ct1";
    /** Retryable kinds; the others must not be retried blindly. */
    static final Set<BrokerException.Kind> RETRYABLE = EnumSet.of(BrokerException.Kind.RATE_LIMIT, BrokerException.Kind.NETWORK,
            BrokerException.Kind.TIMEOUT);

    protected BrokerAdapter adapter;

    /** A fresh adapter with a live session. */
    protected abstract BrokerAdapter connect();

    /** Makes the instrument's last price {@code lastPrice}. */
    protected abstract void givenQuote(String lastPrice);

    /** The error kinds this harness can make the broker return for an order placement. */
    protected abstract Set<BrokerException.Kind> scriptedErrors();

    protected void givenNextPlaceFails(BrokerException.Kind kind) {
        throw new UnsupportedOperationException(kind.name());
    }

    protected void givenPlaceAccepted(String brokerOrderId) {
    }

    /** The broker now reports the order in {@code status} (OPEN or CANCELLED). */
    protected void givenOrder(String brokerOrderId, BrokerOrderStatus status) {
    }

    protected void givenCancelAccepted(String brokerOrderId) {
    }

    protected void givenEmptyAccount() {
    }

    /** Asserts that no order request reached the broker. */
    protected void assertNoOrderSent() {
    }

    @BeforeEach
    void connectAdapter() {
        adapter = connect();
    }

    protected static BrokerOrderRequest limitBuy(UUID instrument) {
        return new BrokerOrderRequest(instrument, Side.BUY, Quantity.of(1), OrderType.LIMIT, Product.MIS, Price.of("1400.00"), null, Validity.DAY, TAG);
    }

    @Test
    void identifiesItselfWithASessionAndACode() {
        assertThat(adapter.brokerCode()).matches("[a-z]+");
        assertThat(adapter.instrumentBrokerCode()).matches("[a-z]+");
        assertThat(adapter.sessionState()).isEqualTo(BrokerSessionState.CONNECTED);
    }

    @Test
    void aPlacedOrderReadsBackAsSentAndCanBeCancelled() {
        givenQuote("1500.00");
        givenPlaceAccepted("C-1001");
        BrokerOrderRef ref = adapter.placeOrder(limitBuy(INSTRUMENT)); // below the market: stays open
        assertThat(ref.brokerOrderId()).isNotBlank();
        givenOrder(ref.brokerOrderId(), BrokerOrderStatus.OPEN);

        BrokerOrder order = adapter.getOrder(ref);
        assertThat(order.brokerOrderId()).isEqualTo(ref.brokerOrderId());
        assertThat(order.instrumentId()).isEqualTo(INSTRUMENT);
        assertThat(order.side()).isEqualTo(Side.BUY);
        assertThat(order.quantity()).isEqualTo(1);
        assertThat(order.orderType()).isEqualTo(OrderType.LIMIT);
        assertThat(order.limitPrice()).isEqualByComparingTo(new BigDecimal("1400.00"));
        assertThat(order.tag()).isEqualTo(TAG);
        assertThat(order.status()).isIn(BrokerOrderStatus.PENDING, BrokerOrderStatus.OPEN);
        assertThat(order.status().isTerminal()).isFalse();
        assertThat(adapter.getOrders()).extracting(BrokerOrder::brokerOrderId).contains(ref.brokerOrderId());

        givenCancelAccepted(ref.brokerOrderId());
        assertThat(adapter.cancelOrder(ref).brokerOrderId()).isEqualTo(ref.brokerOrderId());
        givenOrder(ref.brokerOrderId(), BrokerOrderStatus.CANCELLED);
        assertThat(adapter.getOrder(ref).status()).isIn(BrokerOrderStatus.CANCELLED, BrokerOrderStatus.CANCEL_PENDING);
    }

    @Test
    void quotesComeBackForMappedInstruments() {
        givenQuote("1500.00");
        assertThat(adapter.getQuote(Set.of(INSTRUMENT))).singleElement().satisfies(q -> {
            assertThat(q.instrumentId()).isEqualTo(INSTRUMENT);
            assertThat(q.lastPrice()).isEqualByComparingTo("1500.00");
            assertThat(q.ts()).isNotNull();
        });
    }

    @Test
    void accountReadsHaveTheContractShapes() {
        givenEmptyAccount();
        assertThat(adapter.getPositions()).isNotNull();
        assertThat(adapter.getHoldings()).isNotNull();
        assertThat(adapter.getTrades()).isNotNull();
        Funds funds = adapter.getFunds();
        assertThat(funds.availableCash()).isNotNull();
        assertThat(funds.usedMargin()).isNotNull();
    }

    @Test
    void brokerErrorsMapToKindsWithTheRightRetryability() {
        for (BrokerException.Kind kind : scriptedErrors()) {
            adapter = connect();
            givenNextPlaceFails(kind);
            assertThatThrownBy(() -> adapter.placeOrder(limitBuy(INSTRUMENT))).as(kind.name()).isInstanceOfSatisfying(BrokerException.class, e -> {
                assertThat(e.kind()).isEqualTo(kind);
                assertThat(e.retryable()).as("retryable " + kind).isEqualTo(RETRYABLE.contains(kind));
                assertThat(e.outcomeUnknown()).as("outcome unknown " + kind)
                        .isEqualTo(kind == BrokerException.Kind.NETWORK || kind == BrokerException.Kind.TIMEOUT || kind == BrokerException.Kind.UNKNOWN);
            });
        }
    }

    @Test
    void anUnmappedInstrumentIsAnInputErrorAndNothingIsSent() {
        assertThatThrownBy(() -> adapter.placeOrder(limitBuy(UUID.randomUUID())))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.INPUT));
        assertNoOrderSent();
    }

    @Test
    void clearingTheSessionDisconnects() {
        adapter.clearSession();
        assertThat(adapter.sessionState()).isNotEqualTo(BrokerSessionState.CONNECTED);
    }
}
