package money.hejje.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import money.hejje.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ServerHealthIT extends AbstractIntegrationTest {

    @Test
    void pingIsPublic() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/server/ping", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthNeedsMarketReadAndHasPrdShape() {
        assertThat(rest.getForEntity("/api/v1/server/health", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> response = rest.exchange("/api/v1/server/health", HttpMethod.GET,
                new HttpEntity<>(bearer(adminAccessToken())), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("status", "UP").containsEntry("mode", "PAPER").containsEntry("executionEnabled", true)
                .containsKeys("version", "time", "reasons", "executionServer", "staticIp", "broker", "marketData",
                        "database", "clockSync", "riskEngine", "orderQueue");
        assertThat((Map<String, Object>) body.get("staticIp")).containsEntry("status", "SKIPPED");
        assertThat((Map<String, Object>) body.get("clockSync")).containsEntry("status", "SKIPPED");
        assertThat((Map<String, Object>) body.get("database")).containsEntry("status", "HEALTHY");
        assertThat((Map<String, Object>) body.get("broker")).containsEntry("status", "HEALTHY");
        assertThat((String) ((Map<String, Object>) body.get("broker")).get("detail")).contains("fake CONNECTED");
        assertThat((Map<String, Object>) body.get("orderQueue")).containsEntry("status", "NOT_CONFIGURED");
    }

    @Test
    void actuatorHealthAndPrometheusArePublic() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        ResponseEntity<String> prometheus = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody()).contains("hejje_execution_enabled").contains("hejje_egress_ip_verified").contains("http_server_requests");
    }
}
