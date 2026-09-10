package money.hejje.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import money.hejje.AbstractIntegrationTest;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ExecutionException;
import money.hejje.execution.LeaseProperties;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Active/standby and split brain (PRD 43, plan M5.6). Instances are simulated as executor leases on the shared
 * database, each with its own connection path that can be cut (a network partition); the submission of an order is
 * the fence check followed by recording (instance, epoch). Whatever the schedule — races, partitions, pauses past the
 * TTL, controlled failovers — at most one instance submits in any epoch, and a former active never submits after a
 * takeover. The last tests run the real order path and the REST surface.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ExecutorFailoverIT extends AbstractIntegrationTest {

    static final LeaseProperties TIMING = new LeaseProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), null, Duration.ofMinutes(2));

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;
    @Autowired HejjeClock hejjeClock;
    @Autowired ExecutorLease lease;
    @Autowired ExecutionEngine engine;
    @Autowired FakeBrokerAdapter fake;
    @Autowired InstrumentService instruments;
    @Autowired ClientCredentialService clients;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;

    /** The database as one instance sees it: cutting it is a network partition between that instance and Postgres. */
    static final class PartitionableDataSource extends DelegatingDataSource {
        volatile boolean cut;

        PartitionableDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (cut) {
                throw new SQLException("simulated network partition");
            }
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }

    record Instance(String name, ExecutorLease lease, PartitionableDataSource db) {

        /** A heartbeat: renew or take over; a partitioned instance cannot reach the database. */
        boolean heartbeat() {
            try {
                return lease.acquire();
            } catch (DataAccessException e) {
                return false;
            }
        }
    }

    final List<String> submitted = Collections.synchronizedList(new ArrayList<>());

    Instance instance(String name) {
        PartitionableDataSource db = new PartitionableDataSource(dataSource);
        return new Instance(name, new ExecutorLease(JdbcClient.create(db), hejjeClock, TIMING, name), db);
    }

    /** Sends an "order" if the fence allows it; returns whether it was sent. */
    boolean trySubmit(Instance i) {
        try {
            i.lease().checkFence();
        } catch (ExecutionException.NotActiveExecutor e) {
            return false;
        }
        submitted.add(i.name() + "@" + i.lease().epoch());
        return true;
    }

    @BeforeEach
    void setUp() {
        lease.requireForTest(false); // also clears a failover hold an earlier test left on the shared context's lease
        jdbc.update("DELETE FROM executor_lease");
        clock.setIst("2026-11-25T10:00:00");
    }

    @AfterEach
    void tearDown() {
        lease.requireForTest(false);
        jdbc.update("DELETE FROM executor_lease");
        clock.set(Instant.now());
        lease.acquire();
    }

    @Test
    void racingInstancesNeverBothAcquire() throws Exception {
        Instance a = instance("a");
        Instance b = instance("b");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            Instance i = t % 2 == 0 ? a : b;
            results.add(pool.submit(() -> {
                start.await();
                return i.heartbeat();
            }));
        }
        start.countDown();
        for (Future<Boolean> f : results) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(a.lease().isHeld() ^ b.lease().isHeld()).as("exactly one holds").isTrue();
        Instance winner = a.lease().isHeld() ? a : b;
        Instance loser = winner == a ? b : a;
        assertThat(trySubmit(winner)).isTrue();
        assertThat(trySubmit(loser)).isFalse();
        assertThat(winner.lease().epoch()).isEqualTo(1);
    }

    @Test
    void aPartitionedActiveFailsClosedAndCannotSubmitAfterTheStandbyTakesOver() {
        Instance a = instance("a");
        Instance b = instance("b");
        assertThat(a.heartbeat()).isTrue();
        assertThat(b.heartbeat()).isFalse();
        assertThat(trySubmit(a)).isTrue();
        assertThat(trySubmit(b)).isFalse();

        a.db().cut = true; // a loses the database
        assertThat(trySubmit(a)).as("an unreadable lease refuses").isFalse();
        assertThat(a.heartbeat()).isFalse();
        clock.advance(Duration.ofSeconds(31));
        assertThat(b.heartbeat()).as("the standby takes over after the TTL").isTrue();
        assertThat(b.lease().epoch()).isEqualTo(2);
        assertThat(trySubmit(b)).isTrue();

        a.db().cut = false; // the partition heals: a is now a standby
        assertThat(trySubmit(a)).isFalse();
        assertThat(a.heartbeat()).isFalse();
        assertThat(submitted).containsExactly("a@1", "b@2");
    }

    @Test
    void aPausedActiveResumesAsAStandby() {
        Instance a = instance("a");
        Instance b = instance("b");
        assertThat(a.heartbeat()).isTrue();
        clock.advance(Duration.ofSeconds(45)); // a is paused (GC, suspended VM): no heartbeats past the TTL
        assertThat(b.heartbeat()).isTrue();
        assertThat(a.lease().isHeld()).as("a still believes it is active").isTrue();
        assertThat(trySubmit(a)).as("the fence re-reads the lease").isFalse();
        assertThat(a.lease().isHeld()).isFalse();
        assertThat(trySubmit(b)).isTrue();
        assertThat(submitted).containsExactly("b@2");
    }

    @Test
    void controlledFailoverHandsOverWithoutWaitingAndHoldsTheOldActiveBack() {
        Instance a = instance("a");
        Instance b = instance("b");
        assertThat(a.heartbeat()).isTrue();
        assertThatThrownBy(() -> b.lease().failover()).isInstanceOf(ExecutionException.NotActiveExecutor.class);

        Instant holdUntil = a.lease().failover();
        assertThat(holdUntil).isEqualTo(clock.instant().plus(TIMING.failoverHold()));
        assertThat(trySubmit(a)).isFalse();
        assertThat(b.heartbeat()).as("no wait for the TTL").isTrue();
        assertThat(trySubmit(b)).isTrue();

        clock.advance(Duration.ofSeconds(40)); // b stops renewing; a still holds back
        assertThat(a.heartbeat()).isFalse();
        clock.advance(Duration.ofMinutes(2));
        assertThat(a.heartbeat()).isTrue();
        assertThat(a.lease().epoch()).isEqualTo(3);
        assertThat(trySubmit(b)).isFalse();
        assertThat(trySubmit(a)).isTrue();
        assertThat(submitted).containsExactly("b@2", "a@3");
    }

    @Test
    void underRandomPartitionsPausesAndFailoversAtMostOneInstanceSubmitsPerEpoch() {
        List<Instance> all = List.of(instance("a"), instance("b"), instance("c"));
        Random random = new Random(20260910L);
        Set<String> paused = new LinkedHashSet<>();
        for (int step = 0; step < 600; step++) {
            for (Instance i : all) {
                double r = random.nextDouble();
                if (r < 0.04) {
                    i.db().cut = !i.db().cut;
                } else if (r < 0.08) {
                    if (!paused.remove(i.name())) {
                        paused.add(i.name());
                    }
                } else if (r < 0.10 && i.lease().isHeld() && !i.db().cut) {
                    try {
                        i.lease().failover();
                    } catch (ExecutionException.NotActiveExecutor e) {
                        // it had already lost the lease
                    }
                }
                if (!paused.contains(i.name())) {
                    i.heartbeat();
                }
            }
            clock.advance(Duration.ofSeconds(random.nextInt(16)));
            for (Instance i : all) {
                if (!paused.contains(i.name())) {
                    trySubmit(i);
                }
            }
        }
        Map<Long, Set<String>> byEpoch = new LinkedHashMap<>();
        long last = 0;
        for (String s : submitted) {
            long epoch = Long.parseLong(s.substring(s.indexOf('@') + 1));
            assertThat(epoch).as("epochs never go back").isGreaterThanOrEqualTo(last);
            last = epoch;
            byEpoch.computeIfAbsent(epoch, e -> new LinkedHashSet<>()).add(s.substring(0, s.indexOf('@')));
        }
        assertThat(byEpoch.values()).as("one submitter per epoch").allSatisfy(names -> assertThat(names).hasSize(1));
        assertThat(byEpoch).as("the schedule exercised takeovers").hasSizeGreaterThan(5);
        assertThat(byEpoch.values().stream().flatMap(Set::stream).distinct()).as("more than one instance was active over time").hasSizeGreaterThan(1);
    }

    void tick(UUID instrument) {
        pipeline.onTick(new money.hejje.common.event.MarketTick(instrument, clock.instant(), new java.math.BigDecimal("1500.00"), null, null, 0, 0,
                money.hejje.common.event.MarketTick.Mode.LTP));
    }

    OrderIntentCommand marketBuy(UUID instrument) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "failover-it", null, null, instrument, Side.BUY,
                Quantity.of(1), OrderType.MARKET, Product.MIS, null, null, null, null, null, OrderReason.MANUAL);
    }

    @Test
    void theRealOrderPathIsFencedAtTheBrokerCall() {
        jdbc.execute("TRUNCATE trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE WHERE mode = 'PAPER'");
        jdbc.update("UPDATE risk_limits SET mandatory_stop = FALSE, no_reentry_minutes = 0, max_trades_per_day = 100000 WHERE mode = 'PAPER'");
        instruments.sync();
        UUID infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        fake.injectQuote(infy, "1500.00");
        tick(infy); // streaming may be on in the shared context: readiness wants a fresh tick at the clock's time
        lease.requireForTest(true);
        assertThat(lease.acquire()).isTrue();

        Instance standby = instance("standby");
        clock.advance(Duration.ofSeconds(31)); // this instance paused past the TTL; the standby takes over
        assertThat(standby.heartbeat()).isTrue();
        tick(infy);
        HejjeOrder refused = engine.submit(marketBuy(infy));
        assertThat(refused.state()).isEqualTo(OrderState.REJECTED);
        assertThat(fake.getOrders()).as("nothing reached the broker").isEmpty();
        assertThatThrownBy(() -> engine.cancel(refused.id())).isInstanceOf(ExecutionException.NotActiveExecutor.class);

        standby.lease().failover(); // hand back
        assertThat(lease.acquire()).isTrue();
        HejjeOrder sent = engine.submit(marketBuy(infy));
        fake.flush();
        assertThat(sent.state()).isNotEqualTo(OrderState.REJECTED);
        assertThat(fake.getOrders()).hasSize(1);
    }

    @Test
    void failoverIsAnAdminCommandWithAConfirmation() {
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String reader = clients.create("executor-reader-" + UUID.randomUUID(), Set.of("market:read"), null, actor).key();
        String admin = clients.create("executor-admin-" + UUID.randomUUID(), Set.of("admin", "market:read"), null, actor).key();
        lease.requireForTest(true);
        assertThat(lease.acquire()).isTrue();

        Map<String, Object> status = rest.exchange("/api/v1/server/executor", HttpMethod.GET, new HttpEntity<>(bearer(reader)), Map.class).getBody();
        assertThat(status).containsEntry("role", "ACTIVE").containsEntry("instance", lease.instance());

        assertThat(rest.exchange("/api/v1/server/failover", HttpMethod.POST, new HttpEntity<>(Map.of("confirmation", "FAILOVER"), bearer(reader)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange("/api/v1/server/failover", HttpMethod.POST, new HttpEntity<>(Map.of("confirmation", "yes"), bearer(admin)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> done = rest.exchange("/api/v1/server/failover", HttpMethod.POST, new HttpEntity<>(Map.of("confirmation", "FAILOVER"), bearer(admin)),
                Map.class);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).containsEntry("released", true).containsKey("holdUntil");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'EXECUTOR_FAILOVER'", Long.class)).isPositive();

        Map<String, Object> after = rest.exchange("/api/v1/server/executor", HttpMethod.GET, new HttpEntity<>(bearer(reader)), Map.class).getBody();
        assertThat(after).containsEntry("role", "STANDBY");
        assertThat(after.get("holdUntil")).isNotNull();
        assertThat(rest.exchange("/api/v1/server/failover", HttpMethod.POST, new HttpEntity<>(Map.of("confirmation", "FAILOVER"), bearer(admin)), String.class)
                .getStatusCode()).as("a standby cannot fail over").isEqualTo(HttpStatus.CONFLICT);
    }
}
