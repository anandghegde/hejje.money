package money.hejje.system.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.config.ExecutionProperties;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.system.EgressIpStatus;
import money.hejje.system.EgressIpStatusChanged;
import money.hejje.system.ReadinessCheck;
import money.hejje.system.SystemProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically resolves the public egress IP through several resolvers and compares it with
 * {@code hejje.execution.expected-ips}. State changes are published as {@link EgressIpStatusChanged} and audited.
 */
@Component
public class EgressIpVerifier implements ReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(EgressIpVerifier.class);

    /** Outcome of one verification pass. */
    public record Result(EgressIpStatus status, List<String> observedIps, String detail) {}

    private final boolean enabled;
    private final List<PublicIpResolver> resolvers;
    private final List<String> expectedIps;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final HejjeClock clock;
    private volatile Result last;

    @Autowired
    EgressIpVerifier(SystemProperties system, ExecutionProperties execution, ApplicationEventPublisher events,
            AuditService audit, HejjeClock clock, MeterRegistry meters) {
        this(system.egress().enabled(), httpResolvers(system.egress()), execution.expectedIps(), events, audit, clock, meters);
    }

    EgressIpVerifier(boolean enabled, List<PublicIpResolver> resolvers, List<String> expectedIps,
            ApplicationEventPublisher events, AuditService audit, HejjeClock clock, MeterRegistry meters) {
        this.enabled = enabled;
        this.resolvers = List.copyOf(resolvers);
        this.expectedIps = List.copyOf(expectedIps);
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.last = enabled ? new Result(EgressIpStatus.UNKNOWN, List.of(), "not checked yet")
                : new Result(EgressIpStatus.SKIPPED, List.of(), "egress verification disabled");
        Gauge.builder("hejje_egress_ip_verified", this, v -> v.status() == EgressIpStatus.VERIFIED ? 1 : 0)
                .description("1 when the egress IP matches an expected IP").register(meters);
    }

    private static List<PublicIpResolver> httpResolvers(SystemProperties.Egress egress) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(egress.timeout()).build();
        return egress.resolvers().stream().<PublicIpResolver>map(url -> new HttpPublicIpResolver(url, client, egress.timeout())).toList();
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${hejje.system.egress.check-interval:PT5M}")
    void scheduledCheck() {
        check();
    }

    /** Runs one verification pass, updates the state and emits an event plus audit entry when the status changes. */
    public synchronized Result check() {
        if (!enabled) {
            return last;
        }
        Result fresh = evaluate();
        Result previous = last;
        last = fresh;
        if (previous.status() != fresh.status()) {
            log.warn("Egress IP status {} -> {} ({})", previous.status(), fresh.status(), fresh.detail());
            events.publishEvent(new EgressIpStatusChanged(EventMeta.create(clock), previous.status(), fresh.status(), fresh.observedIps(), expectedIps));
            audit.record(AuditEvent.of(AuditEventType.EGRESS_IP_STATUS_CHANGED, ActorType.SYSTEM).withActorId("egress-ip-verifier")
                    .withPayload(Map.of("previous", previous.status().name(), "current", fresh.status().name(),
                            "observedIps", fresh.observedIps(), "expectedIps", expectedIps, "detail", fresh.detail())));
        }
        return fresh;
    }

    private Result evaluate() {
        Set<String> observed = new LinkedHashSet<>();
        for (PublicIpResolver resolver : resolvers) {
            Optional<String> ip = resolver.resolve();
            ip.ifPresent(observed::add);
        }
        List<String> observedIps = List.copyOf(observed);
        if (observedIps.isEmpty()) {
            return new Result(EgressIpStatus.UNKNOWN, observedIps, "all " + resolvers.size() + " resolvers failed");
        }
        if (expectedIps.isEmpty()) {
            return new Result(EgressIpStatus.MISMATCH, observedIps, "no expected IPs configured; observed " + observedIps);
        }
        if (observedIps.size() > 1) {
            return new Result(EgressIpStatus.MISMATCH, observedIps, "resolvers disagree: " + observedIps);
        }
        String ip = observedIps.get(0);
        if (expectedIps.contains(ip)) {
            return new Result(EgressIpStatus.VERIFIED, observedIps, ip);
        }
        return new Result(EgressIpStatus.MISMATCH, observedIps, "egress " + ip + " not in expected " + expectedIps);
    }

    public EgressIpStatus status() {
        return last.status();
    }

    public Result lastResult() {
        return last;
    }

    @Override
    public String name() {
        return "staticIp";
    }

    @Override
    public CheckResult result() {
        Result r = last;
        return switch (r.status()) {
            case VERIFIED -> CheckResult.ok(r.detail());
            case SKIPPED -> CheckResult.skipped(r.detail());
            case MISMATCH, UNKNOWN -> CheckResult.blocking(r.status() + ": " + r.detail());
        };
    }
}
