package money.hejje.system;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.system.internal.ClockDriftChecker;
import money.hejje.system.internal.DatabaseCheck;
import money.hejje.system.internal.EgressIpVerifier;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/server")
class ServerController {

    static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    private final HejjeProperties properties;
    private final String version;
    private final EgressIpVerifier egress;
    private final ClockDriftChecker clockDrift;
    private final DatabaseCheck database;
    private final ExecutionReadiness readiness;

    ServerController(HejjeProperties properties, Optional<BuildProperties> buildProperties, EgressIpVerifier egress,
            ClockDriftChecker clockDrift, DatabaseCheck database, ExecutionReadiness readiness) {
        this.properties = properties;
        this.version = buildProperties.map(BuildProperties::getVersion).orElse("unknown");
        this.egress = egress;
        this.clockDrift = clockDrift;
        this.database = database;
        this.readiness = readiness;
    }

    record Ping(String status, Instant time) {}

    /** One line of the PRD section 41 health panel. */
    record Check(String status, String detail) {

        static Check notConfigured() {
            return new Check(NOT_CONFIGURED, "arrives in a later milestone");
        }

        /** A readiness-backed line: OK -> HEALTHY, BLOCKING -> DOWN, SKIPPED -> SKIPPED; absent check -> NOT_CONFIGURED. */
        static Check fromReadiness(ReadinessCheck.CheckResult result) {
            if (result == null) {
                return notConfigured();
            }
            return new Check(switch (result.status()) {
                case OK -> "HEALTHY";
                case BLOCKING -> "DOWN";
                case SKIPPED -> "SKIPPED";
            }, result.detail());
        }
    }

    /** PRD section 41 shape plus execution readiness. */
    record Health(
            String status,
            ExecutionMode mode,
            String version,
            Instant time,
            boolean executionEnabled,
            List<String> reasons,
            Check executionServer,
            Check staticIp,
            Check broker,
            Check marketData,
            Check database,
            Check clockSync,
            Check riskEngine,
            Check orderQueue) {}

    /** Public liveness probe; reveals nothing about configuration. */
    @GetMapping("/ping")
    Ping ping() {
        return new Ping("UP", Instant.now());
    }

    @GetMapping("/health")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Health health() {
        boolean dbUp = database.isHealthy();
        EgressIpVerifier.Result ip = egress.lastResult();
        ClockDriftChecker.Result clock = clockDrift.lastResult();
        Map<String, ReadinessCheck.CheckResult> checks = readiness.results();
        return new Health(
                dbUp ? "UP" : "DEGRADED",
                properties.mode(),
                version,
                Instant.now(),
                readiness.isExecutionEnabled(),
                readiness.reasons(),
                new Check("HEALTHY", "mode " + properties.mode()),
                new Check(ip.status().name(), ip.detail()),
                Check.fromReadiness(checks.get("brokerSession")),
                Check.fromReadiness(checks.get("marketData")),
                new Check(dbUp ? "HEALTHY" : "DOWN", dbUp ? "SELECT 1 ok" : "SELECT 1 failed"),
                new Check(clock.status().name(), clock.detail()),
                Check.notConfigured(),
                Check.notConfigured());
    }
}
