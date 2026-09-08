package money.hejje.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.internal.ExecutorLease;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderEventSource;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.risk.RiskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class ExecutionRecoveryIT extends AbstractIntegrationTest {

    @Autowired ReconciliationService reconciliation;
    @Autowired FakeBrokerAdapter fake;
    @Autowired OrderService orders;
    @Autowired InstrumentService instruments;
    @Autowired RiskService risk;
    @Autowired ExecutorLease lease;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient jdbcClient;
    @Autowired MutableClock clock;
    @Autowired HejjeClock hejjeClock;

    UUID infy;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record, reconciliation_issue CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        clock.setIst("2026-09-08T10:00:00");
    }

    @AfterEach
    void rearm() {
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
        jdbc.update("UPDATE reconciliation_issue SET resolved_at = now() WHERE resolved_at IS NULL");
    }

    @Test
    void reconciliationImportsUnknownBrokerOrder() {
        String brokerId = fake.seedOrder(infy, Side.BUY, 10, OrderType.LIMIT, Product.MIS, new BigDecimal("1400.00"),
                BrokerOrderStatus.OPEN, 0, BigDecimal.ZERO);
        List<ReconciliationIssue> issues = reconciliation.reconcile();
        assertThat(issues).anyMatch(i -> i.kind().equals("EXTERNAL_ORDER"));
        assertThat(orders.findByBrokerOrderId("fake", brokerId)).isPresent();
    }

    @Test
    void positionMismatchIsCriticalTripsKillSwitchAndBlocksUntilResolved() {
        fake.seedPosition(infy, Product.MIS, 100, new BigDecimal("1500.00")); // broker +100, Hejje 0
        List<ReconciliationIssue> issues = reconciliation.reconcile();
        assertThat(issues).anyMatch(i -> i.kind().equals("POSITION_MISMATCH") && i.severity() == ReconciliationSeverity.CRITICAL);
        assertThat(risk.killSwitch(ExecutionMode.PAPER).stopNewOrders()).isTrue();
        assertThat(reconciliation.hasCriticalOpen()).isTrue();

        UUID issueId = reconciliation.openIssues().stream().filter(i -> i.kind().equals("POSITION_MISMATCH")).findFirst().orElseThrow().id();
        reconciliation.resolve(issueId);
        assertThat(reconciliation.hasCriticalOpen()).isFalse();
    }

    @Test
    void restoreInFlightMovesSubmittingOrderToOpenViaPoll() {
        String brokerId = fake.seedOrder(infy, Side.BUY, 10, OrderType.LIMIT, Product.MIS, new BigDecimal("1400.00"),
                BrokerOrderStatus.OPEN, 0, BigDecimal.ZERO);
        // a local order stuck in SUBMITTING with that broker id (as if the process died mid-submit)
        UUID id = money.hejje.common.Ids.newId();
        HejjeOrder submitting = new HejjeOrder(id, null, ExecutionMode.PAPER, "fake", brokerId, "tag" + brokerId, infy, Side.BUY, 10, 0,
                BigDecimal.ZERO.setScale(2), OrderType.LIMIT, Product.MIS, new BigDecimal("1400.00"), null, OrderState.SUBMITTING, null,
                clock.instant(), clock.instant(), null, null);
        orders.create(submitting, OrderEventSource.SYSTEM);

        reconciliation.reconcile();
        fake.flush();
        assertThat(orders.findById(id).orElseThrow().state()).isEqualTo(OrderState.OPEN);
    }

    @Test
    void secondProcessCannotAcquireTheLease() {
        // start from a clean lease row so this test is independent of the shared context leases
        jdbc.update("DELETE FROM executor_lease");
        ExecutorLease first = new ExecutorLease(jdbcClient, hejjeClock);
        ExecutorLease second = new ExecutorLease(jdbcClient, hejjeClock);
        assertThat(first.acquire()).isTrue();
        assertThat(second.acquire()).isFalse();
        assertThat(second.currentOwner()).contains(first.owner());
        assertThat(first.acquire()).isTrue(); // the holder can renew
    }
}
