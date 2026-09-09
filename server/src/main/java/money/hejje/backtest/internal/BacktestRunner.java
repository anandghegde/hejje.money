package money.hejje.backtest.internal;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import money.hejje.backtest.BacktestProperties;
import money.hejje.backtest.BacktestService;
import money.hejje.common.time.HejjeClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Bounded pool ({@code hejje.backtest.workers}) that executes queued backtests; keeps per-run cancel flags. */
@Component
public class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);

    private final ExecutorService pool;
    private final Map<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final BacktestStore store;
    private final HejjeClock clock;
    private BacktestService service;

    BacktestRunner(BacktestProperties properties, BacktestStore store, HejjeClock clock) {
        this.store = store;
        this.clock = clock;
        this.pool = Executors.newFixedThreadPool(Math.max(1, properties.workers()), r -> {
            Thread t = new Thread(r, "backtest-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void attach(BacktestService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failLeftovers() {
        int n = store.failUnfinished("server restarted before the backtest finished", clock.now());
        if (n > 0) {
            log.warn("Marked {} unfinished backtest(s) as FAILED after restart", n);
        }
    }

    public void enqueue(UUID id) {
        AtomicBoolean flag = new AtomicBoolean(false);
        cancelFlags.put(id, flag);
        pool.submit(() -> {
            try {
                service.execute(id, flag::get);
            } catch (RuntimeException e) {
                log.error("Backtest {} crashed", id, e);
            } finally {
                cancelFlags.remove(id);
            }
        });
    }

    public void cancel(UUID id) {
        AtomicBoolean flag = cancelFlags.get(id);
        if (flag != null) {
            flag.set(true);
        }
    }

    public boolean isActive(UUID id) {
        return cancelFlags.containsKey(id);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
