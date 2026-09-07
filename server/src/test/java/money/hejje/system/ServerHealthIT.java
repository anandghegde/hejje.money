package money.hejje.system;

import static org.assertj.core.api.Assertions.assertThat;

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
    void healthNeedsMarketReadAndReportsPaperMode() {
        assertThat(rest.getForEntity("/api/v1/server/health", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> response = rest.exchange("/api/v1/server/health", HttpMethod.GET,
                new HttpEntity<>(bearer(adminAccessToken())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"mode\":\"PAPER\"").contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
