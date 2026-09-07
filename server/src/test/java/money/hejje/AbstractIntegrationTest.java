package money.hejje;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/** Boots the full application against a shared Postgres container. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hejje.auth.admin-password=" + AbstractIntegrationTest.ADMIN_PASSWORD,
        "hejje.auth.jwt-secret=test-secret-test-secret-test-secret-test-secret",
        "hejje.auth.rate-limit.default-burst=25",
        "hejje.auth.rate-limit.transactional-burst=5"
})
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    public static final String ADMIN_PASSWORD = "test-admin-password";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    /** Logs in as admin and returns the raw login response (token body plus refresh cookie). */
    protected ResponseEntity<Map> loginAdmin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", "admin", "password", ADMIN_PASSWORD), headers), Map.class);
    }

    protected String adminAccessToken() {
        return (String) loginAdmin().getBody().get("accessToken");
    }

    protected static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected static String refreshCookie(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        return cookies.stream().filter(c -> c.startsWith("hejje_refresh=")).map(c -> c.substring(0, c.indexOf(';')))
                .findFirst().orElse(null);
    }
}
