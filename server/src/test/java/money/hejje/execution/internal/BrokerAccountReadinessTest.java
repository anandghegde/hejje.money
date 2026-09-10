package money.hejje.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import money.hejje.broker.BrokerAccount;
import money.hejje.broker.BrokerAccountService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerSessionService;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.BrokerSessionStatus;
import money.hejje.system.ReadinessCheck.CheckResult.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrokerAccountReadinessTest {

    BrokerAccountService accounts = mock(BrokerAccountService.class);
    BrokerSessionService sessions = mock(BrokerSessionService.class);
    BrokerAdapter broker = mock(BrokerAdapter.class);
    BrokerAccountReadiness check = new BrokerAccountReadiness(accounts, sessions, broker);

    @BeforeEach
    void connectedToZerodha() {
        when(broker.instrumentBrokerCode()).thenReturn("zerodha");
        when(sessions.status()).thenReturn(status(BrokerSessionState.CONNECTED, "AB1234"));
    }

    static BrokerSessionStatus status(BrokerSessionState state, String user) {
        return new BrokerSessionStatus("zerodha", state, user, Instant.now(), null, null, "", true);
    }

    static BrokerAccount account(String broker, String id) {
        return new BrokerAccount(UUID.randomUUID(), broker, id, null, true, Instant.now(), Instant.now(), Instant.now(), "admin");
    }

    @Test
    void onlyTheActiveAccountMayTrade() {
        when(accounts.active()).thenReturn(Optional.of(account("zerodha", "AB1234")));
        assertThat(check.result().status()).isEqualTo(Status.OK);

        when(accounts.active()).thenReturn(Optional.of(account("dhan", "1100003626")));
        assertThat(check.result().status()).isEqualTo(Status.BLOCKING);
        assertThat(check.result().detail()).contains("zerodha/AB1234").contains("dhan/1100003626");

        when(accounts.active()).thenReturn(Optional.of(account("zerodha", "ZZ9999")));
        assertThat(check.result().status()).isEqualTo(Status.BLOCKING);
    }

    @Test
    void noAccountYetNoSessionOrTheFakeBrokerDoNotBlock() {
        when(accounts.active()).thenReturn(Optional.empty());
        assertThat(check.result().status()).isEqualTo(Status.OK);
        when(sessions.status()).thenReturn(status(BrokerSessionState.EXPIRED, "AB1234"));
        assertThat(check.result().status()).isEqualTo(Status.SKIPPED);
        when(broker.instrumentBrokerCode()).thenReturn("fake");
        assertThat(check.result().status()).isEqualTo(Status.SKIPPED);
    }
}
