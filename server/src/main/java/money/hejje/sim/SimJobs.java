package money.hejje.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What every {@code @Scheduled} method does in a SIM instance (plan M7.1), keyed {@code SimpleClassName#method}, in the
 * order the {@link SimScheduler} fires due jobs at each replay step: market data first (candles close), then execution,
 * positions, signals, automation, context and analytics. A job that talks to the outside world or to wall time is
 * skipped with the reason. A new {@code @Scheduled} method must be added here, including one a library brings:
 * {@code SimJobsCoverageTest} (Hejje's classes) and {@code SimModeIT} (the whole context) fail and a SIM instance refuses
 * to start until it is.
 */
public final class SimJobs {

    public enum Policy { RUN, SKIP }

    public record Entry(Policy policy, String reason) {}

    private final Map<String, Entry> entries;

    public SimJobs(Map<String, Entry> entries) {
        this.entries = new LinkedHashMap<>(entries);
    }

    /** The registry of this code base. */
    public static SimJobs standard() {
        Map<String, Entry> m = new LinkedHashMap<>();
        run(m, "MarketPipeline#tick", "closes candles as simulation time passes");
        run(m, "OrderPoller#poll", "order states from the simulated broker");
        run(m, "ReconciliationService#scheduled", "positions against the simulated broker");
        run(m, "ExecutorLease#heartbeat", "the lease uses the Hejje clock, so it lives on simulation time");
        run(m, "ExecutorBootstrap#retryIfWaitingForLease", "enables execution once the lease is held");
        run(m, "OptionsPositionMonitor#tick", "options exits");
        run(m, "SignalHousekeeping#expire", "signal validity");
        run(m, "AutoSignalListener#sweep", "AUTO re-offers actionable signals");
        run(m, "ApprovalExpiry#sweep", "approval expiry");
        run(m, "RegimeRefresh#intradaySnapshot", "regime as of simulation time");
        run(m, "RegimeRefresh#finalLabel", "the session's final regime label");
        run(m, "PulseRefresh#refresh", "Market Pulse as of simulation time");
        run(m, "EventRefresh#daily", "event calendar from config files");
        run(m, "ScoreRefresh#refreshDeployed", "Hejje Score of deployed versions");
        run(m, "DriftSweep#sweep", "live-vs-backtest drift of simulated trades");
        run(m, "IdempotencyStore#cleanup", "expired idempotency records");
        skip(m, "MarketDataStreamer#reconnectIfNeeded", "no live market stream: the replay feeds the pipeline");
        skip(m, "BrokerSessionService#dailyExpiry", "no broker session: SIM runs only the fake adapter");
        skip(m, "BrokerSessionService#periodicValidation", "no broker session: SIM runs only the fake adapter");
        skip(m, "InstrumentSyncJob#scheduled", "the instrument master is fixed for a simulation");
        skip(m, "NewsPoller#poll", "live news would leak information from after the simulated time");
        skip(m, "NotifyWatchers#digests", "a simulation sends no notifications");
        skip(m, "NotifyWatchers#check", "a simulation sends no notifications");
        skip(m, "EgressIpVerifier#scheduledCheck", "network check of the live host");
        skip(m, "ClockDriftChecker#scheduledCheck", "compares the wall clock with NTP; simulation time is not wall time");
        skip(m, "CandleRetentionJob#prune", "the SIM database is not the operational candle store");
        // library jobs (not covered by SimJobsCoverageTest, which scans money.hejje; SimModeIT boots the full context)
        skip(m, "Moments#everyHour", "Spring Modulith time-passage events on the wall clock; nothing in Hejje listens to them");
        skip(m, "Moments#everyMidnight", "Spring Modulith time-passage events on the wall clock; nothing in Hejje listens to them");
        return new SimJobs(m);
    }

    private static void run(Map<String, Entry> m, String key, String reason) {
        m.put(key, new Entry(Policy.RUN, reason));
    }

    private static void skip(Map<String, Entry> m, String key, String reason) {
        m.put(key, new Entry(Policy.SKIP, reason));
    }

    public Map<String, Entry> entries() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    public Entry get(String key) {
        return entries.get(key);
    }

    /** Position of the key in the firing order; unknown keys sort last. */
    public int order(String key) {
        int i = 0;
        for (String k : entries.keySet()) {
            if (k.equals(key)) {
                return i;
            }
            i++;
        }
        return Integer.MAX_VALUE;
    }
}
