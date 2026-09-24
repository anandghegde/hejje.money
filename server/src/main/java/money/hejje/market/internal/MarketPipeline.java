package money.hejje.market.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import money.hejje.common.event.MarketTick;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.BarMicro;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The single entry point every tick flows through, live or replayed: update the quote cache, publish the tick on the
 * bus, build candles and their order-book/flow data (plan M9.4), persist and publish closed candles, and optionally
 * record the tick. A scheduled tick every second
 * closes minutes whose boundary has passed (so empty minutes still produce synthetic candles).
 */
@Component
public class MarketPipeline {

    private final TickBus bus;
    private final QuoteCache quotes;
    private final CandleBuilder candles = new CandleBuilder();
    private final BarMicroBuilder micro = new BarMicroBuilder();
    private final MarketCandleStore candleStore;
    private final ObjectProvider<TickRecorder> recorder;
    private final HejjeClock clock;
    private volatile Instant lastTickAt;

    MarketPipeline(TickBus bus, QuoteCache quotes, MarketCandleStore candleStore, ObjectProvider<TickRecorder> recorder, HejjeClock clock) {
        this.bus = bus;
        this.quotes = quotes;
        this.candleStore = candleStore;
        this.recorder = recorder;
        this.clock = clock;
    }

    public void onTick(MarketTick tick) {
        lastTickAt = tick.ts();
        quotes.accept(tick);
        recorder.ifAvailable(r -> r.record(tick));
        bus.publish(tick);
        List<Candle> closed = candles.onTick(tick);
        micro.onTick(tick); // keyed by the tick's own minute, so the closed minutes above are unaffected
        publishClosed(closed);
    }

    /** Closes minutes up to {@code now} (synthetic fill for empty minutes). Called on a schedule and by replay. */
    public void closeCandlesAsOf(Instant now) {
        publishClosed(candles.onClock(now));
    }

    @Scheduled(fixedRate = 1000)
    void tick() {
        closeCandlesAsOf(clock.now());
    }

    public Optional<Instant> lastTickAt() {
        return Optional.ofNullable(lastTickAt);
    }

    private void publishClosed(List<Candle> closed) {
        for (Candle candle : closed) {
            candleStore.save(candle);
            BarMicro m = micro.onCandleClosed(candle);
            if (m != null) {
                candleStore.saveMicro(m);
            }
            bus.publish(new CandleClosedEvent(candle, m));
        }
    }

    /**
     * Starts a SIM session afresh (plan M7.2): open bars, cumulative volumes and cached quotes of a previous session are
     * dropped without publishing anything.
     */
    public void resetForSimulation() {
        candles.flushAll();
        candles.clearVolumes();
        micro.clear();
        quotes.clear();
        lastTickAt = null;
    }

    /** Test/session-end hook: close all open bars. */
    public void flush() {
        publishClosed(candles.flushAll());
    }
}
