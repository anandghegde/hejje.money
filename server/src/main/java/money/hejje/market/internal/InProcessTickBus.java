package money.hejje.market.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import money.hejje.common.event.MarketEvent;
import money.hejje.common.event.TickBus;
import money.hejje.market.MarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-process, non-durable fan-out for {@link MarketEvent}s. Producers call {@link #publish} from any thread; a single
 * dispatcher thread delivers to listeners so ordering is total and listeners never race. The queue is bounded and drops
 * the oldest event on overflow, incrementing {@code hejje_tick_bus_dropped_total}. In SIM (plan M7.2) events are
 * dispatched inline on the publishing thread instead, so a replay step has finished its listeners when publish returns.
 */
@Component
public class InProcessTickBus implements TickBus, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InProcessTickBus.class);

    private final BlockingQueue<MarketEvent> queue;
    private final List<Consumer<? super MarketEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Counter dropped;
    private final Counter published;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread dispatcher;
    private final boolean inline;

    public InProcessTickBus(MarketProperties properties, MeterRegistry meters, money.hejje.common.config.HejjeProperties hejje) {
        this.inline = hejje.mode() == money.hejje.common.ExecutionMode.SIM;
        this.queue = new ArrayBlockingQueue<>(Math.max(1024, properties.tickQueue()));
        this.dropped = Counter.builder("hejje_tick_bus_dropped_total").description("market events dropped on tick-bus overflow").register(meters);
        this.published = Counter.builder("hejje_tick_bus_published_total").description("market events published to the tick bus").register(meters);
        meters.gauge("hejje_tick_bus_queue_depth", queue, java.util.Queue::size);
        this.dispatcher = new Thread(this::run, "tick-bus");
        this.dispatcher.setDaemon(true);
        if (!inline) {
            this.dispatcher.start();
        }
    }

    @Override
    public void publish(MarketEvent event) {
        published.increment();
        if (inline) {
            dispatchInline(event);
            return;
        }
        while (!queue.offer(event)) {
            if (queue.poll() != null) {
                dropped.increment();
            }
        }
    }

    @Override
    public AutoCloseable subscribe(Consumer<? super MarketEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void run() {
        while (running.get() || !queue.isEmpty()) {
            try {
                MarketEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                for (Consumer<? super MarketEvent> listener : listeners) {
                    try {
                        listener.accept(event);
                    } catch (RuntimeException e) {
                        log.warn("Tick bus listener failed", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void dispatchInline(MarketEvent event) {
        for (Consumer<? super MarketEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.warn("Tick bus listener failed", e);
            }
        }
    }

    /** Blocks until every already-published event has been dispatched (tests). */
    public void drain() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        // one more slice so the in-flight event finishes dispatching
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        dispatcher.interrupt();
    }
}
