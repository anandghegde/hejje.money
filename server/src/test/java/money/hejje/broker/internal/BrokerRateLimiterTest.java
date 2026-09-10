package money.hejje.broker.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerProperties;
import org.junit.jupiter.api.Test;

class BrokerRateLimiterTest {

    static BrokerRateLimiter limiter() {
        return new BrokerRateLimiter(new BrokerProperties("fake", "http://x", new BrokerProperties.Limits(10, 200, 3000, 1, 3, 10, 50), java.util.Map.of()),
                new SimpleMeterRegistry());
    }

    @Test
    void eleventhOrderInASecondIsRejected() {
        BrokerRateLimiter limiter = limiter();
        for (int i = 0; i < 10; i++) {
            limiter.acquireOrder();
        }
        assertThatThrownBy(limiter::acquireOrder).isInstanceOfSatisfying(BrokerException.class,
                e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.RATE_LIMIT));
        assertThat(limiter.orderTokensAvailable()).isZero();
    }

    @Test
    void quoteBucketIsOnePerSecond() {
        BrokerRateLimiter limiter = new BrokerRateLimiter(new BrokerProperties("fake", "http://x",
                new BrokerProperties.Limits(10, 200, 3000, 1, 3, 10, 50), java.util.Map.of()), new SimpleMeterRegistry());
        limiter.acquireRead(BrokerRateLimiter.Op.QUOTE);
        assertThatThrownBy(() -> limiter.acquireRead(BrokerRateLimiter.Op.QUOTE))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.kind()).isEqualTo(BrokerException.Kind.RATE_LIMIT));
    }
}
