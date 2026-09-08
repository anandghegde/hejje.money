package money.hejje.system;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import java.util.Map;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.system.internal.EgressIpVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Egress verification enabled against a fake resolver that reports an IP other than the expected one. */
class EgressMismatchIT extends AbstractIntegrationTest {

    @RegisterExtension
    static WireMockExtension resolver = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void egress(DynamicPropertyRegistry registry) {
        registry.add("hejje.system.egress.enabled", () -> "true");
        registry.add("hejje.system.egress.check-interval", () -> "PT1H");
        registry.add("hejje.system.egress.resolvers", () -> resolver.baseUrl() + "/ip-a," + resolver.baseUrl() + "/ip-b");
        registry.add("hejje.execution.expected-ips", () -> "203.0.113.10");
    }

    @Autowired
    EgressIpVerifier verifier;

    @Autowired
    ExecutionReadiness readiness;

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void wrongEgressIpBlocksExecutionAndShowsInHealth() {
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        resolver.stubFor(get("/ip-a").willReturn(aResponse().withStatus(200).withBody("198.51.100.7\n")));
        resolver.stubFor(get("/ip-b").willReturn(aResponse().withStatus(500)));

        assertThat(verifier.check().status()).isEqualTo(EgressIpStatus.MISMATCH);
        assertThat(readiness.isExecutionEnabled()).isFalse();
        assertThat(readiness.reasons()).anyMatch(r -> r.startsWith("staticIp: MISMATCH"));

        ResponseEntity<Map> health = rest.exchange("/api/v1/server/health", HttpMethod.GET, new HttpEntity<>(bearer(adminAccessToken())), Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> staticIp = (Map<String, Object>) health.getBody().get("staticIp");
        assertThat(staticIp).containsEntry("status", "MISMATCH");
        assertThat(health.getBody()).containsEntry("executionEnabled", false);
        assertThat((List<String>) health.getBody().get("reasons")).isNotEmpty();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.EGRESS_IP_STATUS_CHANGED, null, 0, 10)).total()).isPositive();

        resolver.stubFor(get("/ip-a").willReturn(aResponse().withStatus(200).withBody("203.0.113.10")));
        resolver.stubFor(get("/ip-b").willReturn(aResponse().withStatus(200).withBody("203.0.113.10")));
        assertThat(verifier.check().status()).isEqualTo(EgressIpStatus.VERIFIED);
        assertThat(readiness.isExecutionEnabled()).isTrue();
    }
}
