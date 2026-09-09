package money.hejje.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.ActorType;
import money.hejje.common.CorrelationId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Uses the shared context and the real HTTP port (a MockMvc context would boot a second application against the same database). */
class AuditIT extends AbstractIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recordsAndQueriesEvents() {
        UUID orderId = UUID.randomUUID();
        CorrelationId correlationId = CorrelationId.newId();
        AuditRecord saved = audit.record(AuditEvent.of(AuditEventType.ORDER_SUBMITTED, ActorType.USER)
                .withActorId("admin").withOrderId(orderId).withCorrelationId(correlationId)
                .withClientSource("tui").withPayload(Map.of("symbol", "NSE:INFY", "qty", 10)));

        AuditPage page = audit.query(new AuditQuery(null, null, AuditEventType.ORDER_SUBMITTED, orderId, 0, 10));
        assertThat(page.total()).isEqualTo(1);
        AuditRecord found = page.content().get(0);
        assertThat(found.id()).isEqualTo(saved.id());
        assertThat(found.correlationId()).isEqualTo(correlationId);
        assertThat(found.payload()).containsEntry("symbol", "NSE:INFY").containsEntry("qty", 10);
        assertThat(found.clientSource()).isEqualTo("tui");
    }

    @Test
    void auditRowsAreImmutable() {
        AuditRecord saved = audit.record(AuditEvent.of(AuditEventType.KILL_SWITCH_ENABLED, ActorType.SYSTEM));

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET actor_id = 'x' WHERE id = ?", saved.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", saved.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE id = ?", Long.class, saved.id())).isEqualTo(1L);
    }

    @Test
    void endpointIsPaged() throws Exception {
        UUID orderId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            audit.record(AuditEvent.of(AuditEventType.ORDER_FILLED, ActorType.SYSTEM).withOrderId(orderId));
        }
        HttpEntity<Void> auth = new HttpEntity<>(bearer(adminAccessToken()));
        ResponseEntity<Map> page = rest.exchange("/api/v1/audit?orderId=" + orderId + "&type=ORDER_FILLED&size=2", HttpMethod.GET, auth, Map.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody().get("total")).isEqualTo(3);
        assertThat(page.getBody().get("size")).isEqualTo(2);
        java.util.List<Map<?, ?>> content = (java.util.List<Map<?, ?>>) page.getBody().get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("type")).isEqualTo("ORDER_FILLED");
        assertThat(content.get(0).get("correlationId")).isInstanceOf(String.class);
        ResponseEntity<Map> bad = rest.exchange("/api/v1/audit?type=NOT_A_TYPE", HttpMethod.GET, auth, Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
