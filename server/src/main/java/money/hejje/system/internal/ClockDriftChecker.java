package money.hejje.system.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import money.hejje.common.time.HejjeClock;
import money.hejje.system.ReadinessCheck;
import money.hejje.system.SystemProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Compares the local clock with a remote HTTP {@code Date} header. Drift beyond the limit marks the clock DEGRADED. */
@Component
public class ClockDriftChecker implements ReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(ClockDriftChecker.class);

    public enum Status { HEALTHY, DEGRADED, UNKNOWN, SKIPPED }

    public record Result(Status status, Duration drift, String detail) {}

    private final boolean enabled;
    private final RemoteTimeSource remote;
    private final Duration maxDrift;
    private final HejjeClock clock;
    private volatile Result last;

    @Autowired
    ClockDriftChecker(SystemProperties properties, HejjeClock clock, MeterRegistry meters) {
        this(properties.clock().enabled(),
                new HttpDateHeaderTimeSource(properties.clock().host(),
                        HttpClient.newBuilder().connectTimeout(properties.clock().timeout()).build(), properties.clock().timeout()),
                properties.clock().maxDrift(), clock, meters);
    }

    ClockDriftChecker(boolean enabled, RemoteTimeSource remote, Duration maxDrift, HejjeClock clock, MeterRegistry meters) {
        this.enabled = enabled;
        this.remote = remote;
        this.maxDrift = maxDrift;
        this.clock = clock;
        this.last = enabled ? new Result(Status.UNKNOWN, null, "not checked yet") : new Result(Status.SKIPPED, null, "clock drift check disabled");
        Gauge.builder("hejje_clock_drift_seconds", this, c -> c.last.drift() == null ? Double.NaN : c.last.drift().toMillis() / 1000.0)
                .description("Local clock minus remote reference clock, in seconds").register(meters);
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${hejje.system.clock.check-interval:PT10M}")
    void scheduledCheck() {
        check();
    }

    public synchronized Result check() {
        if (!enabled) {
            return last;
        }
        Optional<Instant> remoteNow = remote.remoteNow();
        Result fresh;
        if (remoteNow.isEmpty()) {
            fresh = new Result(Status.UNKNOWN, null, "reference clock unreachable");
        } else {
            Duration drift = Duration.between(remoteNow.get(), clock.now());
            boolean ok = drift.abs().compareTo(maxDrift) <= 0;
            fresh = new Result(ok ? Status.HEALTHY : Status.DEGRADED, drift, "drift " + drift.toMillis() + " ms (limit " + maxDrift.toMillis() + " ms)");
        }
        if (fresh.status() != last.status()) {
            log.warn("Clock sync {} -> {} ({})", last.status(), fresh.status(), fresh.detail());
        }
        last = fresh;
        return fresh;
    }

    public Result lastResult() {
        return last;
    }

    @Override
    public String name() {
        return "clockSync";
    }

    @Override
    public CheckResult result() {
        Result r = last;
        return switch (r.status()) {
            case HEALTHY -> CheckResult.ok(r.detail());
            case SKIPPED -> CheckResult.skipped(r.detail());
            case UNKNOWN -> CheckResult.ok("UNKNOWN: " + r.detail()); // an unreachable reference does not block execution
            case DEGRADED -> CheckResult.blocking("DEGRADED: " + r.detail());
        };
    }
}
