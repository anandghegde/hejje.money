package money.hejje.market.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerCandle;
import money.hejje.common.Ids;
import money.hejje.common.Timeframe;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.market.MarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Backfills historical candles from the broker into the Parquet store. Minute data is chunked into <=60-day requests;
 * a simple per-second throttle stands in for the M1.6 rate limiter. Jobs run on a small pool and report progress.
 */
@Component
public class HistoricalBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(HistoricalBackfillJob.class);
    private static final int MINUTE_CHUNK_DAYS = 60;

    /** Progress of one backfill job. */
    public record Progress(UUID jobId, UUID instrumentId, Timeframe timeframe, Instant from, Instant to, String status,
            int chunksTotal, int chunksDone, long candlesWritten, String error) {
    }

    private final BrokerAdapter broker;
    private final HistoricalCandleStore store;
    private final MarketProperties properties;
    private final Map<UUID, Progress> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "backfill");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastRequestAt;

    HistoricalBackfillJob(BrokerAdapter broker, HistoricalCandleStore store, MarketProperties properties) {
        this.broker = broker;
        this.store = store;
        this.properties = properties;
    }

    static List<Instant[]> chunks(Timeframe timeframe, Instant from, Instant to) {
        int maxDays = timeframe == Timeframe.M1 || timeframe == Timeframe.M3 ? MINUTE_CHUNK_DAYS : 100000;
        List<Instant[]> chunks = new java.util.ArrayList<>();
        Instant start = from;
        while (start.isBefore(to)) {
            Instant end = start.plus(Duration.ofDays(maxDays));
            if (end.isAfter(to)) {
                end = to;
            }
            chunks.add(new Instant[]{start, end});
            start = end.plusSeconds(1);
        }
        return chunks;
    }

    public UUID start(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        UUID jobId = Ids.newId();
        List<Instant[]> chunks = chunks(timeframe, from, to);
        jobs.put(jobId, new Progress(jobId, instrumentId, timeframe, from, to, "RUNNING", chunks.size(), 0, 0, null));
        pool.submit(() -> run(jobId, instrumentId, timeframe, chunks));
        return jobId;
    }

    /** Runs a backfill synchronously (tests). Returns candles written. */
    public long runNow(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        UUID jobId = Ids.newId();
        List<Instant[]> chunks = chunks(timeframe, from, to);
        jobs.put(jobId, new Progress(jobId, instrumentId, timeframe, from, to, "RUNNING", chunks.size(), 0, 0, null));
        run(jobId, instrumentId, timeframe, chunks);
        return jobs.get(jobId).candlesWritten();
    }

    private void run(UUID jobId, UUID instrumentId, Timeframe timeframe, List<Instant[]> chunks) {
        long written = 0;
        int done = 0;
        try {
            for (Instant[] chunk : chunks) {
                throttle();
                List<BrokerCandle> raw = broker.getHistory(instrumentId, timeframe, chunk[0], chunk[1]);
                List<Candle> candles = raw.stream().map(c -> new Candle(instrumentId, timeframe, c.openTime(), c.open(), c.high(),
                        c.low(), c.close(), c.volume(), c.oi(), false)).toList();
                store.write(instrumentId, timeframe, candles);
                written += candles.size();
                done++;
                Progress p = jobs.get(jobId);
                jobs.put(jobId, new Progress(jobId, instrumentId, timeframe, p.from(), p.to(), "RUNNING", chunks.size(), done, written, null));
            }
            Progress p = jobs.get(jobId);
            jobs.put(jobId, new Progress(jobId, instrumentId, timeframe, p.from(), p.to(), "DONE", chunks.size(), done, written, null));
        } catch (RuntimeException e) {
            log.warn("Backfill {} failed at chunk {}: {}", jobId, done, e.getMessage());
            Progress p = jobs.get(jobId);
            jobs.put(jobId, new Progress(jobId, instrumentId, timeframe, p.from(), p.to(), "FAILED", chunks.size(), done, written, e.getMessage()));
        }
    }

    private void throttle() {
        long minGap = 1000L / Math.max(1, properties.historicalPerSecond());
        long wait = lastRequestAt + minGap - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    public Progress progress(UUID jobId) {
        return jobs.get(jobId);
    }
}
