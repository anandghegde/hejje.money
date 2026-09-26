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

    // --- GTT (plan M11.2) -------------------------------------------------------------------------------------------------

    /** How the adapter supports GTTs: simulated (fake, paper; the contract below), at the broker (tested with its harness), or not at all. */
    protected enum GttSupport { SIMULATED, BROKER, NONE }

    protected GttSupport gttSupport() {
        return GttSupport.NONE;
    }

    protected static Gtt.Request ocoStop(String last, String stop, String goal, int qty) {
        return new Gtt.Request(INSTRUMENT, Gtt.Type.OCO, java.util.List.of(new BigDecimal(stop), new BigDecimal(goal)), new BigDecimal(last), java.util.List.of(
                new Gtt.Leg(Side.SELL, qty, OrderType.MARKET, new BigDecimal(stop), Product.CNC),
                new Gtt.Leg(Side.SELL, qty, OrderType.LIMIT, new BigDecimal(goal), Product.CNC)));
    }

    @Test
    void gttsArePlacedListedModifiedAndCancelled() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gttSupport() == GttSupport.SIMULATED);
        givenQuote("1500.00");
        String id = adapter.placeGtt(ocoStop("1500.00", "1400.00", "1700.00", 5));
        assertThat(adapter.getGtts()).filteredOn(g -> g.id().equals(id)).singleElement().satisfies(g -> {
            assertThat(g.status()).isEqualTo(Gtt.Status.ACTIVE);
            assertThat(g.type()).isEqualTo(Gtt.Type.OCO);
            assertThat(g.instrumentId()).isEqualTo(INSTRUMENT);
            assertThat(g.triggers()).usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("1400.00"), new BigDecimal("1700.00"));
            assertThat(g.quantity()).isEqualTo(5);
            assertThat(g.legs()).extracting(Gtt.Leg::product).containsOnly(Product.CNC);
        });

        assertThat(adapter.modifyGtt(id, ocoStop("1500.00", "1450.00", "1700.00", 3))).isEqualTo(id);
        assertThat(adapter.getGtts()).filteredOn(g -> g.id().equals(id)).singleElement().satisfies(g -> {
            assertThat(g.triggers().get(0)).isEqualByComparingTo("1450.00");
            assertThat(g.quantity()).isEqualTo(3);
        });

        assertThat(adapter.cancelGtt(id)).isEqualTo(id);
        assertThat(adapter.getGtts()).filteredOn(g -> g.id().equals(id)).singleElement().satisfies(g -> assertThat(g.status().isLive()).isFalse());
        assertThatThrownBy(() -> adapter.modifyGtt(id, ocoStop("1500.00", "1450.00", "1700.00", 3)))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.REJECTED));
        assertThatThrownBy(() -> adapter.placeGtt(new Gtt.Request(UUID.randomUUID(), Gtt.Type.SINGLE, java.util.List.of(new BigDecimal("10")),
                new BigDecimal("11"), java.util.List.of(new Gtt.Leg(Side.SELL, 1, OrderType.MARKET, new BigDecimal("10"), Product.CNC)))))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.INPUT));
    }

    @Test
    void aGapDownThroughTheStopFillsTheStopLegAtTheOpen() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gttSupport() == GttSupport.SIMULATED);
        givenQuote("1500.00");
        String id = adapter.placeGtt(ocoStop("1500.00", "1400.00", "1700.00", 5));
        givenQuote("1350.00"); // the next session opens 50 below the stop
        Gtt.Snapshot fired = adapter.getGtts().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
        assertThat(fired.status()).isEqualTo(Gtt.Status.TRIGGERED);
        assertThat(fired.triggeredOrderId()).isNotBlank();
        BrokerOrder exit = adapter.getOrder(new BrokerOrderRef(fired.triggeredOrderId()));
        assertThat(exit.side()).isEqualTo(Side.SELL);
        assertThat(exit.product()).isEqualTo(Product.CNC);
        assertThat(exit.quantity()).isEqualTo(5);
        assertThat(exit.status()).isEqualTo(BrokerOrderStatus.COMPLETE);
        // filled at the open (less slippage where the adapter models it), not at the stop
        assertThat(exit.averagePrice()).isLessThanOrEqualTo(new BigDecimal("1350.00")).isGreaterThan(new BigDecimal("1349.00"));
        givenQuote("1800.00"); // an OCO ends with its first leg: the goal leg never fires
        assertThat(adapter.getOrders()).filteredOn(o -> o.side() == Side.SELL).hasSize(1);
    }

    @Test
    void theGoalLegOfAnOcoFillsAtTheGoal() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gttSupport() == GttSupport.SIMULATED);
        givenQuote("1500.00");
        String id = adapter.placeGtt(ocoStop("1500.00", "1400.00", "1700.00", 5));
        givenQuote("1650.00");
        assertThat(adapter.getGtts()).filteredOn(g -> g.id().equals(id)).singleElement().satisfies(g -> assertThat(g.status()).isEqualTo(Gtt.Status.ACTIVE));
        givenQuote("1710.00");
        Gtt.Snapshot fired = adapter.getGtts().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
        assertThat(fired.status()).isEqualTo(Gtt.Status.TRIGGERED);
        BrokerOrder exit = adapter.getOrder(new BrokerOrderRef(fired.triggeredOrderId()));
        assertThat(exit.orderType()).isEqualTo(OrderType.LIMIT);
        assertThat(exit.averagePrice()).isEqualByComparingTo("1700.00");
    }

    @Test
    void anAdapterWithoutGttsRefusesThemCleanly() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gttSupport() == GttSupport.NONE);
        assertThatThrownBy(() -> adapter.placeGtt(ocoStop("1500.00", "1400.00", "1700.00", 5)))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BrokerException.Kind.INPUT);
                    assertThat(e.retryable()).isFalse();
                });
        assertThatThrownBy(() -> adapter.getGtts()).isInstanceOf(BrokerException.class);
        assertNoOrderSent();
    }

    @Test
    void clearingTheSessionDisconnects() {
        adapter.clearSession();
        assertThat(adapter.sessionState()).isNotEqualTo(BrokerSessionState.CONNECTED);
    }
}
