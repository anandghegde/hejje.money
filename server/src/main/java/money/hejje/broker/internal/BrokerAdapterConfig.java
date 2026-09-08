package money.hejje.broker.internal;

import io.micrometer.core.instrument.MeterRegistry;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.broker.zerodha.ZerodhaKiteAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Makes the rate-limited, timed wrapper the primary {@link BrokerAdapter}. The raw adapter (fake or zerodha) is selected
 * by {@code hejje.broker.adapter} and injected here; components that inject the interface get the wrapper, while those
 * that need the concrete adapter (postback controller, tests) still inject it by its class.
 */
@Configuration
class BrokerAdapterConfig {

    @Bean
    @Primary
    BrokerAdapter rateLimitedBrokerAdapter(ObjectProvider<FakeBrokerAdapter> fake, ObjectProvider<ZerodhaKiteAdapter> zerodha,
            BrokerRateLimiter limiter, MeterRegistry meters) {
        BrokerAdapter delegate = fake.getIfAvailable();
        if (delegate == null) {
            delegate = zerodha.getObject();
        }
        return new RateLimitedBrokerAdapter(delegate, limiter, meters);
    }
}
