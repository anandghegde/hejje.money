package money.hejje.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Universe;
import money.hejje.instruments.UniverseCatalog;
import money.hejje.market.internal.HistoricalBackfillJob;
import org.springframework.stereotype.Service;

/** Historical candles for a whole universe file (plan M8.1): the fan-out backfill and the evening D1 refresh. */
@Service
public class UniverseHistory {

    private final UniverseCatalog universes;
    private final HistoricalBackfillJob backfill;
    private final HejjeClock clock;

    UniverseHistory(UniverseCatalog universes, HistoricalBackfillJob backfill, HejjeClock clock) {
        this.universes = universes;
        this.backfill = backfill;
        this.clock = clock;
    }

    /** Starts one parent job over a per-instrument backfill each and returns its id. */
    public UUID backfill(String universe, Timeframe timeframe, Instant from, Instant to) {
        Universe.Resolved resolved = universes.resolve(universe);
        return backfill.startUniverse(resolved.universe().name(), resolved.instruments(), resolved.unresolved(), timeframe, from, to);
    }

    public UniverseBackfill progress(UUID jobId) {
        return backfill.universeProgress(jobId);
    }

    /**
     * Re-fetches the D1 candles of the last {@code sessions} trading days up to today for every instrument of the
     * universe and waits for it. Writing is an upsert, so a repeat changes nothing; a stale universe needs a larger
     * {@code sessions} once.
     */
    public UniverseBackfill refreshDaily(String universe, int sessions) {
        Universe.Resolved resolved = universes.resolve(universe);
        LocalDate today = clock.today();
        LocalDate from = today;
        for (int counted = clock.isTradingDay(today) ? 1 : 0; counted < sessions; ) {
            from = from.minusDays(1);
            if (clock.isTradingDay(from)) {
                counted++;
            }
        }
        return backfill.runUniverseNow(resolved.universe().name(), resolved.instruments(), resolved.unresolved(), Timeframe.D1,
                from.atStartOfDay(clock.zone()).toInstant(), clock.now());
    }
}
