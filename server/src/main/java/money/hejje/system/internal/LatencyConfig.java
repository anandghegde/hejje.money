package money.hejje.system.internal;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Enables client-side percentiles (p50/p95/p99) on the PRD section 44 latency timers. */
@Configuration
class LatencyConfig {

    static final Set<String> TIMERS = Set.of("broker.call", "broker.ack", "risk.evaluate", "api.request", "http.server.requests");

    @Bean
    MeterFilter latencyPercentiles() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (id.getType() == Meter.Type.TIMER && TIMERS.contains(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .percentiles(0.5, 0.95, 0.99)
                            .percentilesHistogram(false)
                            .build().merge(config);
                }
                return config;
            }
        };
    }
}
