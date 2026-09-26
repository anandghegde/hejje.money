package money.hejje.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.ZoneId;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerSessionState;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import money.hejje.execution.ReconciliationService;
import money.hejje.orders.OrderService;
import org.junit.jupiter.api.Test;

class ExecutorBootstrapTest {

    @Test
    void retriesBootstrapOnceTheLeaseIsHeld() {
        ExecutorLease lease = mock(ExecutorLease.class);
        when(lease.required()).thenReturn(true);
        when(lease.owner()).thenReturn("this-process");
        // predecessor's lease still live at startup, then expired and taken over by the heartbeat
        when(lease.acquire()).thenReturn(false, true);
        when(lease.isHeld()).thenReturn(false, true);
        BrokerAdapter broker = mock(BrokerAdapter.class);
        when(broker.sessionState()).thenReturn(BrokerSessionState.DISCONNECTED);
        HejjeProperties properties = new HejjeProperties(ExecutionMode.PAPER, ZoneId.of("Asia/Kolkata"), Path.of("./data"));
        ExecutorBootstrap bootstrap = new ExecutorBootstrap(lease, broker, mock(OrderService.class), mock(ReconciliationService.class),
                mock(UnknownOrderResolver.class), mock(AuditService.class), properties, mock(money.hejje.execution.GttService.class));

        bootstrap.run();
        assertThat(bootstrap.result().allowsExecution()).isFalse();
        assertThat(bootstrap.result().detail()).contains("held by another instance");

        bootstrap.retryIfWaitingForLease(); // heartbeat has not acquired yet
        assertThat(bootstrap.result().allowsExecution()).isFalse();

        bootstrap.retryIfWaitingForLease(); // now held
        assertThat(bootstrap.result().allowsExecution()).isTrue();
        assertThat(bootstrap.result().detail()).isEqualTo("execution enabled");
    }
}
