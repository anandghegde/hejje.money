package money.hejje.analogs;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import money.hejje.analogs.internal.AnalogReads;
import money.hejje.analogs.internal.DailyAnalogComputer;
import money.hejje.analogs.internal.SessionAnalogComputer;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.InstrumentService;
import org.springframework.stereotype.Service;

/**
 * Public API of the analogs module: stored evidence per symbol, session and lookback, the ranked universe, and the
 * jobs that compute it. Reads never see a session that has not closed on the Hejje clock (the SIM look-ahead guard).
 */
@Service
public class AnalogsService {

    /** One row of the ranked universe: a symbol's outcome for the chosen lookback and forward window. */
    public record Ranked(String symbol, int lookback, String qualityTag, int matches, AnalogSummary.Outcome outcome) {
    }

    private final AnalogsProperties props;
    private final DailyAnalogComputer daily;
    private final AnalogReads reads;
    private final SessionAnalogComputer sessions;
    private final InstrumentService instruments;
    private final HejjeClock clock;

    AnalogsService(AnalogsProperties props, DailyAnalogComputer daily, AnalogReads reads, SessionAnalogComputer sessions,
            InstrumentService instruments, HejjeClock clock) {
        this.sessions = sessions;
        this.instruments = instruments;
        this.clock = clock;
        this.props = props;
        this.daily = daily;
        this.reads = reads;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public List<Integer> lookbacks() {
        return props.lookbacks();
    }

    /** Computes the daily analogs of the dates (whole universe when {@code symbols} is empty); stored keys are skipped. */
    public AnalogComputeResult computeDaily(List<LocalDate> dates, Set<String> symbols, List<Integer> lookbacks) {
        return daily.compute(dates, symbols, lookbacks);
    }

    public Optional<AnalogSummary> daily(String symbol, int lookback, LocalDate date) {
        return reads.session(AnalogKind.DAILY, date).flatMap(d -> reads.find(symbol.trim().toUpperCase(), d, AnalogKind.DAILY, lookback, ""));
    }

    /** The matches behind a summary, unordered; empty once they have been pruned. */
    public Optional<List<AnalogMatch>> dailyMatches(String symbol, int lookback, LocalDate date) {
        return reads.session(AnalogKind.DAILY, date).flatMap(d -> reads.matches(symbol.trim().toUpperCase(), d, AnalogKind.DAILY, lookback, ""));
    }

    /** Every daily summary of the session of {@code date} (all lookbacks). */
    public List<AnalogSummary> dailySummaries(LocalDate date) {
        return reads.session(AnalogKind.DAILY, date).map(d -> reads.forDate(d, AnalogKind.DAILY)).orElse(List.of());
    }

    /** The universe ranked by one outcome statistic; {@code minCount} keeps thin evidence off the top. */
    public List<Ranked> rank(int lookback, int forward, String sort, int minCount, LocalDate date) {
        Comparator<Ranked> order = switch (sort == null ? "winRate" : sort) {
            case "winRate" -> Comparator.comparingDouble((Ranked r) -> r.outcome().winRate()).reversed();
            case "median" -> Comparator.comparingDouble((Ranked r) -> r.outcome().median()).reversed();
            case "count" -> Comparator.comparingInt((Ranked r) -> r.outcome().count()).reversed();
            case "reliability" -> Comparator.comparingInt((Ranked r) -> List.of("INSUFFICIENT", "LOW", "MEDIUM", "HIGH").indexOf(r.outcome().reliability()))
                    .reversed().thenComparing(Comparator.comparingInt((Ranked r) -> r.outcome().count()).reversed());
            default -> throw new IllegalArgumentException("sort must be one of winRate, median, count, reliability");
        };
        return dailySummaries(date).stream().filter(s -> s.lookback() == lookback)
                .flatMap(s -> s.outcomes().stream().filter(o -> o.forward().equals(String.valueOf(forward)) && o.count() >= minCount)
                        .map(o -> new Ranked(s.symbol(), s.lookback(), s.qualityTag(), s.matches(), o)))
                .sorted(order.thenComparing(Ranked::symbol)).toList();
    }

    public Optional<LocalDate> latestDaily(LocalDate date) {
        return reads.session(AnalogKind.DAILY, date);
    }

    // --- session analogs (plan M8.6) ---

    public List<String> checkpoints() {
        return props.session().checkpoints();
    }

    /**
     * The session analogs of a symbol at a checkpoint of {@code date} (null: today; {@code checkpoint} null: the latest
     * that has passed). Computed on demand when missing: a summary is a function of stored bars up to the checkpoint and
     * of earlier sessions, so it is the same whenever it is computed. Empty before the first checkpoint, for a
     * checkpoint that has not passed on the Hejje clock, or without the bars.
     */
    public Optional<AnalogSummary> session(String symbol, String checkpoint, LocalDate date) {
        LocalDate day = date == null || date.isAfter(clock.today()) ? clock.today() : date;
        List<String> due = sessions.due(day, clock.now());
        String at = checkpoint == null ? (due.isEmpty() ? null : due.get(due.size() - 1)) : checkpoint;
        if (at == null || !due.contains(at)) {
            return Optional.empty();
        }
        String key = symbol.trim().toUpperCase();
        Optional<AnalogSummary> stored = reads.find(key, day, AnalogKind.SESSION, lookbackOf(at), at);
        if (stored.isPresent()) {
            return stored;
        }
        return instruments.resolve(key).flatMap(i -> {
            sessions.compute(day, at, List.of(i.id()));
            return reads.find(key, day, AnalogKind.SESSION, lookbackOf(at), at);
        });
    }

    public Optional<List<AnalogMatch>> sessionMatches(String symbol, String checkpoint, LocalDate date) {
        return session(symbol, checkpoint, date)
                .flatMap(s -> reads.matches(s.symbol(), s.sessionDate(), AnalogKind.SESSION, s.lookback(), s.checkpoint()));
    }

    /** Computes a checkpoint for the instruments now (the scheduled job and tests); returns the symbols written. */
    public List<String> computeSession(LocalDate date, String checkpoint, java.util.Collection<java.util.UUID> instrumentIds) {
        return sessions.compute(date, checkpoint, instrumentIds);
    }

    /** A session summary's lookback is the number of M5 bars closed by its checkpoint. */
    private static int lookbackOf(String checkpoint) {
        return (int) (java.time.Duration.between(java.time.LocalTime.of(9, 15), java.time.LocalTime.parse(checkpoint)).toMinutes() / 5);
    }
}
