package money.hejje.broker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.broker.zerodha.KitePostback;
import money.hejje.system.ExecutionReadiness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class BrokerSessionIT extends AbstractIntegrationTest {

    @Autowired
    money.hejje.common.time.MutableClock clock;

    @Autowired
    BrokerSessionService sessions;

    @Autowired
    FakeBrokerAdapter fake;

    @Autowired
    ExecutionReadiness readiness;

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void cleanReadiness() {
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
    }

    @AfterEach
    void reconnect() {
        if (!sessions.isConnected()) {
            sessions.completeLogin("good");
        }
    }

    @Test
    void statusLoginUrlLogoutAndLoginRoundTrip() {
        // Sunday: the session is closed, so market-data readiness is SKIPPED whether or not an earlier suite started streaming
        clock.setIst("2026-09-13T10:00:00");
        String token = adminAccessToken();
        ResponseEntity<Map> status = rest.exchange("/api/v1/broker/status", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).containsEntry("broker", "fake").containsEntry("state", "CONNECTED").containsEntry("liveTradingEnabled", true);
        assertThat(readiness.results().get("brokerSession").allowsExecution()).isTrue();

        ResponseEntity<Map> loginUrl = rest.exchange("/api/v1/broker/login-url", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(loginUrl.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) loginUrl.getBody().get("loginUrl")).contains("api_key=fake");

        ResponseEntity<Map> logout = rest.exchange("/api/v1/broker/logout", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logout.getBody()).containsEntry("state", "DISCONNECTED").containsEntry("liveTradingEnabled", false);
        assertThat(readiness.isExecutionEnabled()).isFalse();
        assertThat(readiness.reasons()).anySatisfy(r -> assertThat(r).startsWith("brokerSession: fake DISCONNECTED"));
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.BROKER_LOGGED_OUT, null, 0, 10)).total()).isGreaterThanOrEqualTo(1);

        ResponseEntity<Map> health = rest.exchange("/api/v1/server/health", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat((Map<String, Object>) health.getBody().get("broker")).containsEntry("status", "DOWN");
        assertThat(health.getBody()).containsEntry("executionEnabled", false);

        ResponseEntity<Map> login = rest.exchange("/api/v1/broker/login?request_token=abc", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).containsEntry("state", "CONNECTED").containsEntry("brokerUserId", "FAKE001");
        assertThat(login.getBody().get("expiresAt")).isNotNull();
        assertThat(readiness.isExecutionEnabled()).isTrue();

        Map<String, Object> row = jdbc.queryForMap("SELECT access_token_enc, status FROM broker_session WHERE broker = 'fake'");
        assertThat(row.get("status")).isEqualTo("CONNECTED");
        assertThat((String) row.get("access_token_enc")).startsWith("v1:").doesNotContain("fake-access-token");
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.BROKER_CONNECTED, null, 0, 10)).total()).isGreaterThanOrEqualTo(1);
    }

    private java.net.http.HttpResponse<Void> callback(String query) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        return client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(rest.getRootUri() + "/api/v1/broker/callback?" + query)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void callbackRedirectsToWebClient() throws Exception {
        java.net.http.HttpResponse<Void> ok = callback("request_token=xyz&status=success");
        assertThat(ok.statusCode()).isEqualTo(302);
        assertThat(ok.headers().firstValue("Location").orElseThrow()).endsWith("/broker?connected=1");

        java.net.http.HttpResponse<Void> failed = callback("request_token=bad&status=success");
        assertThat(failed.statusCode()).isEqualTo(302);
        assertThat(failed.headers().firstValue("Location").orElseThrow()).endsWith("/broker?error=auth");
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.BROKER_LOGIN_FAILED, null, 0, 10)).total()).isGreaterThanOrEqualTo(1);

        java.net.http.HttpResponse<Void> cancelled = callback("status=cancelled");
        assertThat(cancelled.headers().firstValue("Location").orElseThrow()).endsWith("/broker?error=login_cancelled");
    }

    @Test
    void brokerSideExpiryDisconnectsOnValidation() {
        long before = audit.query(new AuditQuery(null, null, AuditEventType.BROKER_DISCONNECTED, null, 0, 10)).total();
        fake.simulateSessionExpiry();
        BrokerSessionStatus status = sessions.validate();
        assertThat(status.state()).isEqualTo(BrokerSessionState.DISCONNECTED);
        assertThat(status.detail()).contains("simulated token expiry");
        assertThat(readiness.isExecutionEnabled()).isFalse();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.BROKER_DISCONNECTED, null, 0, 10)).total()).isEqualTo(before + 1);
    }

    @Test
    void postbackVerifiesChecksum() {
        String secret = "test-kite-secret";
        String body = """
                {"order_id":"151220000000001","order_timestamp":"2026-09-08 09:20:01","status":"COMPLETE","exchange":"NSE","tradingsymbol":"INFY",
                 "transaction_type":"BUY","quantity":1,"filled_quantity":1,"pending_quantity":0,"average_price":1498.2,"order_type":"MARKET",
                 "product":"MIS","price":0,"trigger_price":0,"validity":"DAY","tag":"hj1","checksum":"%s"}
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String good = KitePostback.checksum("151220000000001", "2026-09-08 09:20:01", secret);
        ResponseEntity<Void> accepted = rest.postForEntity("/api/v1/broker/postback", new HttpEntity<>(body.formatted(good), headers), Void.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Void> rejected = rest.postForEntity("/api/v1/broker/postback", new HttpEntity<>(body.formatted("deadbeef"), headers), Void.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.postForEntity("/api/v1/broker/postback", new HttpEntity<>("not json", headers), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
