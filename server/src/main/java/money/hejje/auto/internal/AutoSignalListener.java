package money.hejje.auto.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.auto.AutoExecutor;
import money.hejje.signals.SignalGeneratedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands every new signal to the {@link AutoExecutor} on one worker thread (never the signal engine's own thread). The
 * engine publishes outside a transaction, so this is a plain (fallback-executing) listener rather than a durable module
 * listener; the sweep on startup and every {@code hejje.auto.sweep} re-offers anything missed, and executed signals are
 * no longer actionable, so a signal is never entered twice.
 */
@Component
class AutoSignalListener implements money.hejje.common.Drainable {

    private static final Logger log = LoggerFactory.getLogger(AutoSignalListener.class);

    private final AutoExecutor auto;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-executor"); // a platform thread: decisions block on JDBC inside synchronized sections
        t.setDaemon(true);
        return t;
    });

    AutoSignalListener(AutoExecutor auto) {
        this.auto = auto;
    }

    @Override
    public void drain() {
        try {
            worker.submit(() -> { }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("AUTO worker did not drain", e);
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onSignal(SignalGeneratedEvent event) {
        worker.submit(() -> {
            try {
                auto.onSignal(event.signalId());
            } catch (RuntimeException e) {
                log.warn("AUTO decision for signal {} failed", event.signalId(), e);
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${hejje.auto.sweep:PT30S}", initialDelayString = "PT1M")
    void sweep() {
        worker.submit(() -> {
            try {
                auto.sweep();
            } catch (RuntimeException e) {
                log.warn("AUTO sweep failed", e);
            }
        });
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
