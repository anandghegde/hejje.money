package money.hejje.execution.internal;

import java.net.InetAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.ExecutionException;
import money.hejje.execution.LeaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single-writer executor lease (PRD 43/63, active/standby since M5.6). One process owns the {@code executor_lease}
 * row and renews it every {@code heartbeat}; a standby takes it over once it has not been renewed for {@code ttl}, or at
 * once after a controlled {@link #failover}. Every change of owner increments {@code epoch}, the fencing token:
 * {@link #checkFence} re-reads the row right before an order goes to the broker and refuses unless this process still
 * owns it at the epoch it acquired and the lease is live — so a paused or partitioned former active can never submit
 * once a standby has taken over, and a database it cannot reach fails closed.
 */
@Component
public class ExecutorLease implements money.hejje.system.ReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(ExecutorLease.class);

    private final JdbcClient jdbc;
    private final HejjeClock clock;
    private final LeaseProperties properties;
    private final String owner = UUID.randomUUID().toString();
    private final String instance;
    private volatile boolean required;
    private volatile boolean held;
    private volatile long epoch;
    private volatile Instant holdUntil;

    @org.springframework.beans.factory.annotation.Autowired
    ExecutorLease(JdbcClient jdbc, HejjeClock clock, LeaseProperties properties, org.springframework.core.env.Environment environment) {
        // multiple @SpringBootTest contexts share one DB and one lease row, so the lease does not gate execution in tests
        this(jdbc, clock, properties, properties.instance(), !Arrays.asList(environment.getActiveProfiles()).contains("test"));
    }

    /** For direct tests of the lease semantics: required, default timings. */
    public ExecutorLease(JdbcClient jdbc, HejjeClock clock) {
        this(jdbc, clock, LeaseProperties.defaults(), null, true);
    }

    /** For tests simulating several instances: required, with the given timings and name. */
    public ExecutorLease(JdbcClient jdbc, HejjeClock clock, LeaseProperties properties, String instance) {
        this(jdbc, clock, properties, instance, true);
    }

    private ExecutorLease(JdbcClient jdbc, HejjeClock clock, LeaseProperties properties, String instance, boolean required) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.instance = instance != null && !instance.isBlank() ? instance : hostName();
        this.required = required;
    }

    public boolean required() {
        return required;
    }

    /** Tests: makes the shared test context's lease gate execution like in production (the test profile turns it off). */
    void requireForTest(boolean required) {
        this.required = required;
        this.holdUntil = null;
    }

    /**
     * Attempts to acquire or renew the lease; true when this process holds it. Taking it from another owner (whose lease
     * expired or was released) increments the epoch. Refused while this process holds back after a failover.
     */
    public synchronized boolean acquire() {
        Instant now = clock.now();
        if (holdUntil != null && now.isBefore(holdUntil)) {
            held = false;
            return false;
        }
        Optional<Long> acquired = jdbc.sql("""
                INSERT INTO executor_lease (id, owner, acquired_at, expires_at, epoch, instance) VALUES (1, :owner, :now, :expires, 1, :instance)
                ON CONFLICT (id) DO UPDATE SET owner = :owner, expires_at = :expires, instance = :instance,
                    acquired_at = CASE WHEN executor_lease.owner = :owner THEN executor_lease.acquired_at ELSE :now END,
                    epoch = CASE WHEN executor_lease.owner = :owner THEN executor_lease.epoch ELSE executor_lease.epoch + 1 END
                WHERE executor_lease.owner = :owner OR executor_lease.expires_at < :now
                RETURNING epoch
                """)
                .param("owner", owner).param("now", ts(now)).param("expires", ts(now.plus(properties.ttl()))).param("instance", instance)
                .query(Long.class).optional();
        held = acquired.isPresent();
        acquired.ifPresent(e -> epoch = e);
        return held;
    }

    @Scheduled(fixedDelayString = "${hejje.execution.lease.heartbeat:PT10S}")
    void heartbeat() {
        boolean wasHeld = held;
        boolean nowHeld;
        try {
            nowHeld = acquire();
        } catch (DataAccessException e) {
            held = false; // cannot renew: fail closed
            nowHeld = false;
            log.error("Executor lease renewal failed ({}); this process is read-only until it can renew", e.getClass().getSimpleName());
        }
        if (wasHeld && !nowHeld) {
            log.error("Lost the executor lease; another process owns it or it could not be renewed. This process is now a standby.");
        }
    }

    /** Gives the lease up at once (its expiry is set to now) so a standby takes over on its next heartbeat. */
    public synchronized void release() {
        jdbc.sql("UPDATE executor_lease SET expires_at = :past WHERE id = 1 AND owner = :owner")
                .param("past", ts(clock.now().minusMillis(1))).param("owner", owner).update();
        held = false;
    }

    /**
     * Controlled failover (PRD 43): the active releases the lease and does not contend for {@code failover-hold}, so the
     * standby acquires it. Returns the end of the hold.
     *
     * @throws ExecutionException.NotActiveExecutor when this process is not the active executor
     */
    public synchronized Instant failover() {
        checkFence();
        release();
        holdUntil = clock.now().plus(properties.failoverHold());
        log.warn("Controlled failover: executor lease released at epoch {}; standing by until {}", epoch, holdUntil);
        return holdUntil;
    }

    /**
     * The fence before any broker order call: re-reads the lease and throws unless this process owns it, at the epoch it
     * acquired, unexpired. An unreadable lease refuses too. A no-op when the lease is not required (tests).
     */
    public void checkFence() {
        if (!required) {
            return;
        }
        Optional<Row> row;
        try {
            row = current();
        } catch (DataAccessException e) {
            held = false;
            throw new ExecutionException.NotActiveExecutor("executor lease unreadable (" + e.getClass().getSimpleName() + "); nothing sent to the broker");
        }
        Instant now = clock.now();
        if (row.isEmpty() || !owner.equals(row.get().owner()) || row.get().epoch() != epoch || !row.get().expiresAt().isAfter(now)) {
            held = false;
            String who = row.map(r -> r.expiresAt().isAfter(now) ? r.instance() + " (epoch " + r.epoch() + ")" : "nobody (expired)").orElse("nobody");
            throw new ExecutionException.NotActiveExecutor("this instance (" + instance + ") is not the active executor; the lease is held by " + who
                    + "; nothing sent to the broker");
        }
    }

    /** True when this process may do executor work (holds the lease, or the lease is not required). */
    public boolean isActive() {
        return !required || held;
    }

    public boolean isHeld() {
        return held;
    }

    public Optional<String> currentOwner() {
        return jdbc.sql("SELECT owner FROM executor_lease WHERE id = 1 AND expires_at >= :now").param("now", ts(clock.now()))
                .query(String.class).optional();
    }

    public String owner() {
        return owner;
    }

    public String instance() {
        return instance;
    }

    public long epoch() {
        return epoch;
    }

    /** This instance's role and the lease row as it is now. */
    public record Status(String instance, String role, boolean required, boolean held, long epoch, String activeInstance, Long activeEpoch,
            Instant expiresAt, Instant holdUntil) {}

    public Status status() {
        Optional<Row> row = current();
        Instant now = clock.now();
        boolean live = row.isPresent() && row.get().expiresAt().isAfter(now);
        String role = !required ? "NOT_REQUIRED" : held ? "ACTIVE" : "STANDBY";
        return new Status(instance, role, required, held, epoch, live ? row.get().instance() : null, live ? row.get().epoch() : null,
                row.map(Row::expiresAt).orElse(null), holdUntil != null && holdUntil.isAfter(now) ? holdUntil : null);
    }

    private record Row(String owner, long epoch, Instant expiresAt, String instance) {}

    private Optional<Row> current() {
        return jdbc.sql("SELECT owner, epoch, expires_at, instance FROM executor_lease WHERE id = 1").query(ExecutorLease::row).optional();
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getString("owner"), rs.getLong("epoch"), rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("instance"));
    }

    @Override
    public String name() {
        return "executorLease";
    }

    @Override
    public CheckResult result() {
        if (!required) {
            return CheckResult.skipped("executor lease not required (test)");
        }
        return held ? CheckResult.ok("active executor " + instance + " (epoch " + epoch + ")")
                : CheckResult.blocking("standby: the executor lease is held by another instance; read-only");
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "instance-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
