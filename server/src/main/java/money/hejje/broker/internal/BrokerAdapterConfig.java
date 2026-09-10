package money.hejje.broker.internal;

import io.micrometer.core.instrument.MeterRegistry;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerInstrumentResolver;
import money.hejje.broker.BrokerOrderUpdates;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.broker.paper.PaperBrokerAdapter;
import money.hejje.broker.paper.PaperBrokerProperties;
import money.hejje.broker.zerodha.ZerodhaKiteAdapter;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Makes the rate-limited, timed wrapper the primary {@link BrokerAdapter}. The raw adapter (fake, zerodha or dhan) is selected
 * by {@code hejje.broker.adapter} and injected here; components that inject the interface get the wrapper, while those
 * that need the concrete adapter (postback controller, tests) still inject it by its class.
 */
@Configuration
class BrokerAdapterConfig {

    @Bean
    @Primary
    BrokerAdapter brokerAdapter(ObjectProvider<FakeBrokerAdapter> fake, ObjectProvider<ZerodhaKiteAdapter> zerodha,
            ObjectProvider<money.hejje.broker.dhan.DhanAdapter> dhan,
            BrokerRateLimiter limiter, MeterRegistry meters, BrokerInstrumentResolver instruments, BrokerOrderUpdates updates,
            HejjeClock clock, HejjeProperties properties, PaperBrokerProperties paperProperties) {
        FakeBrokerAdapter fakeAdapter = fake.getIfAvailable();
        ZerodhaKiteAdapter kite = fakeAdapter == null ? zerodha.getIfAvailable() : null;
        BrokerAdapter raw = fakeAdapter != null ? fakeAdapter : kite != null ? kite : dhan.getObject();
        BrokerAdapter rateLimited = new RateLimitedBrokerAdapter(raw, limiter, meters);
        // PAPER mode with a real (zerodha, dhan) adapter uses live data but simulates fills; the fake is already a paper sim.
        if (properties.mode() == ExecutionMode.PAPER && fakeAdapter == null) {
            return new PaperBrokerAdapter(rateLimited, instruments, updates, clock, paperProperties);
        }
        return rateLimited;
    }
}
