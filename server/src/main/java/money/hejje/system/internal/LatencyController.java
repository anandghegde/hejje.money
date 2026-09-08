package money.hejje.system.internal;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Latency dashboard (PRD section 44): p50/p95/p99 per timer, in milliseconds. */
@RestController
@RequestMapping("/api/v1/server/latency")
class LatencyController {

    private final MeterRegistry meters;

    LatencyController(MeterRegistry meters) {
        this.meters = meters;
    }

    record TimerLatency(String name, String op, long count, double p50Ms, double p95Ms, double p99Ms, double maxMs, double meanMs) {}

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<TimerLatency> latency() {
        List<TimerLatency> out = new ArrayList<>();
        for (Meter meter : meters.getMeters()) {
            if (!(meter instanceof Timer timer) || !LatencyConfig.TIMERS.contains(meter.getId().getName())) {
                continue;
            }
            if (timer.count() == 0) {
                continue;
            }
            var snapshot = timer.takeSnapshot();
            double p50 = 0;
            double p95 = 0;
            double p99 = 0;
            for (ValueAtPercentile v : snapshot.percentileValues()) {
                double ms = v.value(TimeUnit.MILLISECONDS);
                if (v.percentile() == 0.5) p50 = ms;
                else if (v.percentile() == 0.95) p95 = ms;
                else if (v.percentile() == 0.99) p99 = ms;
            }
            out.add(new TimerLatency(meter.getId().getName(), meter.getId().getTag("op"), timer.count(), round(p50), round(p95),
                    round(p99), round(timer.max(TimeUnit.MILLISECONDS)), round(timer.mean(TimeUnit.MILLISECONDS))));
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
