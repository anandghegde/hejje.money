package money.hejje.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.system.ExecutionReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Broker accounts (plan M5.6): registration on login, one active transactional account, admin activation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class BrokerAccountIT extends AbstractIntegrationTest {

    @Autowired BrokerAccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientCredentialService clients;
    @Autowired ExecutionReadiness readiness;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE broker_account");
    }

    @Test
    void theFirstAccountIsActiveAndOnlyOneEverIs() {
        BrokerAccount kite = accounts.register("zerodha", "AB1234");
        assertThat(kite.active()).isTrue();
        BrokerAccount dhan = accounts.register("dhan", "1100003626");
        assertThat(dhan.active()).isFalse();
        assertThat(accounts.register("dhan", "1100003626").id()).isEqualTo(dhan.id()); // idempotent
        assertThat(accounts.list()).hasSize(2);

        BrokerAccount switched = accounts.activate(dhan.id(), "it");
        assertThat(switched.active()).isTrue();
        assertThat(switched.activatedBy()).isEqualTo("it");
        assertThat(accounts.active()).map(BrokerAccount::broker).contains("dhan");
        assertThat(accounts.list()).filteredOn(BrokerAccount::active).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'BROKER_ACCOUNT_ACTIVATED' AND payload->>'previous' = 'zerodha/AB1234'",
                Long.class)).isPositive();

        // the database refuses a second active account whatever the code does
        assertThatThrownBy(() -> jdbc.update("UPDATE broker_account SET active = TRUE WHERE broker = 'zerodha'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountsAreListedAndActivatedByAnAdminOverRest() {
        BrokerAccount kite = accounts.register("zerodha", "AB1234");
        BrokerAccount dhan = accounts.register("dhan", "1100003626");
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String reader = clients.create("accounts-reader-" + UUID.randomUUID(), Set.of("market:read"), null, actor).key();
        String admin = clients.create("accounts-admin-" + UUID.randomUUID(), Set.of("admin", "market:read"), null, actor).key();

        Map<String, Object> view = rest.exchange("/api/v1/broker/accounts", HttpMethod.GET, new HttpEntity<>(bearer(reader)), Map.class).getBody();
        assertThat(view).containsEntry("adapter", "fake");
        assertThat((List<Map<String, Object>>) view.get("accounts")).extracting(a -> a.get("accountId")).containsExactly("AB1234", "1100003626");
        assertThat((Map<String, Object>) view.get("active")).containsEntry("id", kite.id().toString());

        assertThat(rest.exchange("/api/v1/broker/accounts/" + dhan.id() + "/activate", HttpMethod.POST, new HttpEntity<>(bearer(reader)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> activated = rest.exchange("/api/v1/broker/accounts/" + dhan.id() + "/activate", HttpMethod.POST,
                new HttpEntity<>(bearer(admin)), Map.class);
        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) activated.getBody().get("note")).contains("hejje.broker.adapter=dhan");
        assertThat(rest.exchange("/api/v1/broker/accounts/" + UUID.randomUUID() + "/activate", HttpMethod.POST, new HttpEntity<>(bearer(admin)),
                String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // with the fake broker the account check stays out of the way
        assertThat(readiness.results().get("brokerAccount").allowsExecution()).isTrue();
    }
}
