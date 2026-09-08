package money.hejje.execution.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.time.HejjeClock;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single-writer executor lease (PRD section 43/63 step 1). One process owns the {@code executor_lease} row; it renews
 * every 10 s. A second process cannot acquire it while the lease is live and stays read-only. Standby failover is Phase 5.
 */
@Component
public class ExecutorLease implements money.hejje.system.ReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(ExecutorLease.class);
    static final Duration TTL = Duration.ofSeconds(30);

    private final JdbcClient jdbc;
    private final HejjeClock clock;
    private final String owner = UUID.randomUUID().toString();
    private final boolean required;
    private volatile boolean held;

    @org.springframework.beans.factory.annotation.Autowired
    ExecutorLease(JdbcClient jdbc, HejjeClock clock, org.springframework.core.env.Environment environment) {
        this.jdbc = jdbc;
        this.clock = clock;
        // multiple @SpringBootTest contexts share one DB and one lease row, so the lease does not gate execution in tests
        this.required = !Arrays.asList(environment.getActiveProfiles()).contains("test");
    }

    /** For direct unit/integration tests of the lease SQL semantics. */
    public ExecutorLease(JdbcClient jdbc, HejjeClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.required = true;
    }

    public boolean required() {
        return required;
    }

    /** Attempts to acquire or renew the lease. Returns true when this process holds it. */
    public synchronized boolean acquire() {
        Instant now = clock.now();
        Instant expires = now.plus(TTL);
        int inserted = jdbc.sql("""
                INSERT INTO executor_lease (id, owner, acquired_at, expires_at) VALUES (1, :owner, :now, :expires)
                ON CONFLICT (id) DO UPDATE SET owner = :owner, acquired_at = :now, expires_at = :expires
                WHERE executor_lease.owner = :owner OR executor_lease.expires_at < :now
                """)
                .param("owner", owner).param("now", ts(now)).param("expires", ts(expires)).update();
        held = inserted > 0;
        return held;
    }

    @Scheduled(fixedDelay = 10000)
    void heartbeat() {
        boolean wasHeld = held;
        boolean nowHeld = acquire();
        if (wasHeld && !nowHeld) {
            log.error("Lost the executor lease; another process owns it. This process is now read-only.");
        }
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

    @Override
    public String name() {
        return "executorLease";
    }

    @Override
    public CheckResult result() {
        if (!required) {
            return CheckResult.skipped("executor lease not required (test)");
        }
        return held ? CheckResult.ok("lease held by this process")
                : CheckResult.blocking("executor lease held by another process; read-only");
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
