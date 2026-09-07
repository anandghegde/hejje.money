package money.hejje.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.ActorType;
import money.hejje.common.CorrelationId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuditIT extends AbstractIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

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
        mvc.perform(get("/api/v1/audit").with(user("tester").authorities(new SimpleGrantedAuthority("SCOPE_admin")))
                        .param("orderId", orderId.toString()).param("type", "ORDER_FILLED").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].type").value("ORDER_FILLED"))
                .andExpect(jsonPath("$.content[0].correlationId").isString());
        mvc.perform(get("/api/v1/audit").with(user("tester").authorities(new SimpleGrantedAuthority("SCOPE_admin"))).param("type", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest());
    }
}
