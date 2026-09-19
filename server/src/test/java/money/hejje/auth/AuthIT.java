package money.hejje.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.time.MutableClock;
import org.springframework.beans.factory.annotation.Autowired;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.auth.internal.TokenService;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthIT extends AbstractIntegrationTest {

    @Autowired
    MutableClock testClock;

    @Autowired
    TokenService tokens;

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void loginIssuesJwtAndRefreshCookieAndIsAudited() {
        ResponseEntity<Map> login = loginAdmin();
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).containsKey("accessToken").containsEntry("tokenType", "Bearer");
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).startsWith("hejje_refresh=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");

        ResponseEntity<Map> me = rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer((String) login.getBody().get("accessToken"))), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("name", "admin").containsEntry("type", "USER");
        assertThat((List<String>) me.getBody().get("scopes")).containsExactlyInAnyOrderElementsOf(ScopeCatalog.ALL);

        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.AUTH_LOGIN, null, 0, 10)).total()).isPositive();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username='admin'", String.class))
                .startsWith("$argon2id$");
    }

    @Test
    void wrongPasswordIs401AndAudited() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", "admin", "password", "nope"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.AUTH_LOGIN_FAILED, null, 0, 10)).total()).isPositive();
    }

    @Test
    void noTokenIs401ProblemJson() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/auth/me", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void expiredAccessTokenIs401AndRefreshIssuesNewSession() {
        testClock.set(java.time.Instant.now()); // other suites move the shared clock; this test needs real-time expiry
        ResponseEntity<Map> login = loginAdmin();
        String adminId = (String) rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer((String) login.getBody().get("accessToken"))), Map.class).getBody().get("id");
        HejjePrincipal principal = new HejjePrincipal(UUID.fromString(adminId), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String expired = tokens.issueAccessToken(principal, testClock.instant().minus(Duration.ofMinutes(20)), Duration.ofMinutes(15));
        assertThat(rest.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(bearer(expired)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String cookie = refreshCookie(login);
        HttpHeaders withCookie = new HttpHeaders();
        withCookie.set(HttpHeaders.COOKIE, cookie);
        ResponseEntity<Map> refreshed = rest.postForEntity("/api/v1/auth/refresh", new HttpEntity<>(withCookie), Map.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newToken = (String) refreshed.getBody().get("accessToken");
        assertThat(rest.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(bearer(newToken)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // the old refresh token was rotated away
        ResponseEntity<String> replay = rest.postForEntity("/api/v1/auth/refresh", new HttpEntity<>(withCookie), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // logout revokes the new one
        HttpHeaders newCookie = new HttpHeaders();
        newCookie.set(HttpHeaders.COOKIE, refreshCookie(refreshed));
        assertThat(rest.postForEntity("/api/v1/auth/logout", new HttpEntity<>(newCookie), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.postForEntity("/api/v1/auth/refresh", new HttpEntity<>(newCookie), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void clientCredentialsAreScopedRevocableAndHashed() {
        String admin = adminAccessToken();
        ResponseEntity<Map> created = rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "research-agent", "scopes", List.of("market:read", "strategies:read")), bearer(admin)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String key = (String) created.getBody().get("key");
        assertThat(key).matches("hejje_[a-z0-9]{8}_[A-Za-z0-9_-]{40,}");
        String id = (String) created.getBody().get("id");

        // plaintext never stored
        String secret = key.substring("hejje_".length() + 8 + 1); // the base64url secret itself may contain '_'

        assertThat(jdbc.queryForObject("SELECT secret_hash FROM client_credential WHERE id = ?::uuid", String.class, id))
                .isNotEqualTo(secret).hasSize(64);

        // key works for in-scope calls and is denied for others
        assertThat(rest.exchange("/api/v1/server/health", HttpMethod.GET, new HttpEntity<>(bearer(key)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> forbidden = rest.exchange("/api/v1/auth/clients", HttpMethod.GET, new HttpEntity<>(bearer(key)), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ResponseEntity<Map> me = rest.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(bearer(key)), Map.class);
        assertThat(me.getBody()).containsEntry("type", "CLIENT").containsEntry("name", "research-agent");
        assertThat((List<String>) me.getBody().get("scopes")).containsExactlyInAnyOrder("market:read", "strategies:read");

        // listing never returns the key
        ResponseEntity<List> list = rest.exchange("/api/v1/auth/clients", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class);
        assertThat(list.getBody().toString()).contains("research-agent").doesNotContain(secret);

        // revoke -> 401
        assertThat(rest.exchange("/api/v1/auth/clients/" + id, HttpMethod.DELETE, new HttpEntity<>(bearer(admin)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("/api/v1/server/health", HttpMethod.GET, new HttpEntity<>(bearer(key)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.CLIENT_REVOKED, null, 0, 10)).total()).isPositive();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.CLIENT_CREATED, null, 0, 10)).total()).isPositive();
    }

    @Test
    void unknownScopeIsRejected() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "x", "scopes", List.of("orders:everything")), bearer(adminAccessToken())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Unknown scope");
    }

    @Test
    void websocketHandshakeAcceptsQueryTokenAndRequiresMarketReadForApiKeys() {
        String admin = adminAccessToken();
        String noMarket = (String) rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "no-market", "scopes", List.of("strategies:read")), bearer(admin)), Map.class).getBody().get("key");
        String withMarket = (String) rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "with-market", "scopes", List.of("market:read")), bearer(admin)), Map.class).getBody().get("key");

        assertThat(rest.getForEntity("/ws/market", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/ws/market?token=" + noMarket, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // authenticated: no endpoint exists yet, so anything but 401/403 proves the handshake auth passed
        assertThat(rest.getForEntity("/ws/market?token=" + withMarket, String.class).getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity("/ws/market?token=" + admin, String.class).getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void rateLimitReturns429WithRetryAfter() {
        String key = (String) rest.postForEntity("/api/v1/auth/clients",
                new HttpEntity<>(Map.of("name", "burst", "scopes", List.of("market:read")), bearer(adminAccessToken())), Map.class).getBody().get("key");
        ResponseEntity<String> limited = null;
        for (int i = 0; i < 40 && limited == null; i++) {
            ResponseEntity<String> r = rest.exchange("/api/v1/server/health", HttpMethod.GET, new HttpEntity<>(bearer(key)), String.class);
            if (r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                limited = r;
            }
        }
        assertThat(limited).as("a 429 within the burst window").isNotNull();
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(limited.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void scopeCatalogMatchesPrd() {
        assertThat(ScopeCatalog.ALL).isEqualTo(Set.of("market:read", "strategies:read", "strategies:write", "orders:prepare",
                "orders:execute", "orders:cancel", "positions:close", "risk:read", "risk:write", "admin", "sim:run")); // sim:run: plan M7.2
    }
}
