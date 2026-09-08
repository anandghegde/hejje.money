package money.hejje.broker.internal;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerProperties;
import org.springframework.stereotype.Component;

/**
 * Per-operation broker rate limiter (PRD section 39, Bucket4j). Transactional operations fail fast with
 * {@code RATE_LIMITED} when their bucket is exhausted (orders are never queued); reads wait up to a bounded time.
 */
@Component
public class BrokerRateLimiter {

    public enum Op { ORDER, QUOTE, HISTORICAL, GENERAL }

    private final BrokerProperties.Limits limits;
    private final Bucket orders;
    private final Bucket quotes;
    private final Bucket historical;
    private final Bucket general;
    private final Counter rejections;

    BrokerRateLimiter(BrokerProperties properties, MeterRegistry meters) {
        this.limits = properties.limits();
        this.orders = Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limits.ordersPerSecond()).refillGreedy(limits.ordersPerSecond(), Duration.ofSeconds(1)).build())
                .addLimit(Bandwidth.builder().capacity(limits.ordersPerMinute()).refillGreedy(limits.ordersPerMinute(), Duration.ofMinutes(1)).build())
                .addLimit(Bandwidth.builder().capacity(limits.ordersPerDay()).refillGreedy(limits.ordersPerDay(), Duration.ofDays(1)).build())
                .build();
        this.quotes = perSecond(limits.quotePerSecond());
        this.historical = perSecond(limits.historicalPerSecond());
        this.general = perSecond(limits.generalPerSecond());
        this.rejections = Counter.builder("hejje_broker_rate_limited_total").description("broker calls rejected by the rate limiter").register(meters);
        meters.gauge("hejje_broker_order_tokens", orders, b -> b.getAvailableTokens());
    }

    private static Bucket perSecond(int rate) {
        return Bucket.builder().addLimit(Bandwidth.builder().capacity(rate).refillGreedy(rate, Duration.ofSeconds(1)).build()).build();
    }

    /** Acquires a token for a transactional order op, or throws {@code RATE_LIMITED} immediately. */
    public void acquireOrder() {
        if (!orders.tryConsume(1)) {
            rejections.increment();
            throw new BrokerException(BrokerException.Kind.RATE_LIMIT, "broker order rate limit exceeded", true, null);
        }
    }

    /** Acquires a token for a read op, waiting up to the configured time; throws {@code RATE_LIMITED} on timeout. */
    public void acquireRead(Op op) {
        Bucket bucket = switch (op) {
            case QUOTE -> quotes;
            case HISTORICAL -> historical;
            default -> general;
        };
        try {
            if (!bucket.asBlocking().tryConsume(1, Duration.ofMillis(limits.readWaitMillis()))) {
                rejections.increment();
                throw new BrokerException(BrokerException.Kind.RATE_LIMIT, "broker read rate limit exceeded", true, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerException(BrokerException.Kind.NETWORK, "interrupted waiting for rate limiter", true, e);
        }
    }

    public long orderTokensAvailable() {
        return orders.getAvailableTokens();
    }
}
