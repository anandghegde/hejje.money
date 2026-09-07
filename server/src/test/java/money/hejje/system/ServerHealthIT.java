package money.hejje.system;

import static org.assertj.core.api.Assertions.assertThat;

import money.hejje.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ServerHealthIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthIsPublicAndReportsPaperMode() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/server/health", String.class);
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
