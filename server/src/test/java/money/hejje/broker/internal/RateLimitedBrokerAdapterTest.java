package money.hejje.broker.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerProperties;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Validity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RateLimitedBrokerAdapterTest {

    @Test
    void ordersFailFastAtTheLimitWithoutReachingTheBroker() {
        FakeBrokerAdapter delegate = Mockito.mock(FakeBrokerAdapter.class);
        Mockito.when(delegate.brokerCode()).thenReturn("fake"); // the broker timers are tagged with it (M5.6)
        AtomicInteger calls = new AtomicInteger();
        Mockito.when(delegate.placeOrder(Mockito.any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return new BrokerOrderRef("1");
        });
        BrokerRateLimiter limiter = new BrokerRateLimiter(new BrokerProperties("fake", "http://x",
                new BrokerProperties.Limits(10, 200, 3000, 1, 3, 10, 50), java.util.Map.of()), new SimpleMeterRegistry());
        RateLimitedBrokerAdapter adapter = new RateLimitedBrokerAdapter(delegate, limiter, new SimpleMeterRegistry());

        BrokerOrderRequest request = new BrokerOrderRequest(java.util.UUID.randomUUID(), Side.BUY, Quantity.of(1), OrderType.MARKET,
                Product.MIS, null, null, Validity.DAY, "t");
        for (int i = 0; i < 10; i++) {
            adapter.placeOrder(request);
        }
        assertThatThrownBy(() -> adapter.placeOrder(request)).isInstanceOfSatisfying(BrokerException.class,
                e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.RATE_LIMIT));
        assertThat(calls.get()).isEqualTo(10); // the 11th never reached the broker
    }
}
