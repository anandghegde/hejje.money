package money.hejje.analogs.internal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.analogs.AnalogKind;
import money.hejje.analogs.AnalogMatch;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsProperties;

/**
 * Session analogs (docs/analogs.md, "Session analogs"): the session so far, at a checkpoint, against every past session
 * of the intraday universe at the same time of day, and how those sessions went from there to the exit time.
 *
 * <p>A session is reduced to its M5 closes as returns on the previous close (so the gap is part of the shape) and, per
 * checkpoint, a handful of scalars in multiples of the daily ATR(14). Everything about a session at a checkpoint is a
 * function of bars that closed by the checkpoint and of earlier sessions; candidates are sessions strictly before the
 * benchmark's date. Distances, scores, outcomes and tags are the daily engine's ({@link AnalogMath}, {@link OutcomeStats}).
 */
final class SessionAnalogEngine {

    static final LocalTime OPEN = LocalTime.of(9, 15);
    static final int BAR_MINUTES = 5;
    static final int ATR_SESSIONS = 14;
    static final int RANGE_SESSIONS = 20;
    static final int OPENING_RANGE_BARS = 3;

    /** One M5 bar: {@code index} counts five-minute slots from 09:15. */
    record Bar(int index, double open, double high, double low, double close, double volume) {
    }

    /**
     * A session at one checkpoint.
     *
     * @param scalars     range so far / ATR (volatility slot), return so far / ATR (trend), position in the opening range
     *                    (range position), log cumulative volume vs the same-time average (volume), drawdown so far / ATR (risk)
     * @param priorReturn the previous session's return in ATR multiples
     * @param dailyRange  the previous close within the 20-session daily range, 0..1
     */
    record Snapshot(AnalogMath.Scalars scalars, double priorReturn, double dailyRange, double high, double low, double last) {
    }

    /** Today at a checkpoint: the snapshot and the path (gap, then M5 closes, as returns on the previous close). */
    record Today(Snapshot snapshot, double[] path) {
    }

    /**
     * A complete past session of one instrument, kept small (about 1 kB) because every session of the universe is a candidate.
     *
     * @param path      gap, then the M5 closes up to the last checkpoint, as returns on the previous close; a checkpoint's path is a prefix
     * @param closes    M5 closes up to the exit time
     * @param laterHigh per checkpoint: the highest high from the checkpoint to the exit time
     */
    record Session(int instrument, LocalDate date, double atr, Snapshot[] snapshots, float[] path, float[] closes, double[] laterHigh,
            double[] laterLow, int highBar, int lowBar) {
    }

    record Instrument(UUID id, String symbol) {
    }

    /**
     * One instrument's past: its candidate sessions, and per earlier session the daily high, low and close and the
     * cumulative volume at each checkpoint (what today's snapshot needs for the ATR, the daily range and the volume average).
     */
    record History(List<Session> sessions, List<double[]> daily, List<double[]> cumulativeVolumes) {
    }

    record Analysis(AnalogSummary summary, List<AnalogMatch> matches) {
    }

    private final AnalogsProperties props;
    private final int[] checkpointBars;
    private final int exitBar;

    SessionAnalogEngine(AnalogsProperties props) {
        this.props = props;
        this.checkpointBars = props.session().checkpoints().stream().mapToInt(c -> barsBy(LocalTime.parse(c))).toArray();
        this.exitBar = barsBy(LocalTime.parse(props.session().exitTime())) - 1;
    }

    /** Number of M5 bars that have closed by {@code time}. */
    static int barsBy(LocalTime time) {
        return (int) (java.time.Duration.between(OPEN, time).toMinutes() / BAR_MINUTES);
    }

    int checkpointIndex(String checkpoint) {
        return props.session().checkpoints().indexOf(checkpoint);
    }

    /**
     * Reduces one instrument's sessions, oldest first, to {@link Session}s. A session needs a previous close, 14 earlier
     * sessions for the ATR and every bar up to the exit time; others are skipped as candidates (they still feed the
     * ATR and the volume average of later sessions).
     */
    History history(int instrument, List<LocalDate> dates, List<List<Bar>> bars) {
        List<Session> out = new ArrayList<>();
        List<double[]> daily = new ArrayList<>(); // high, low, close per earlier session
        List<double[]> cumulativeVolumes = new ArrayList<>(); // per earlier session: cumulative volume at each checkpoint
        for (int d = 0; d < dates.size(); d++) {
            List<Bar> session = bars.get(d);
            if (session.isEmpty()) {
                continue;
            }
            double[] volumeAt = cumulativeVolume(session);
            if (daily.size() > ATR_SESSIONS && complete(session)) {
                double atr = atr(daily);
                Snapshot[] snapshots = new Snapshot[checkpointBars.length];
                for (int c = 0; c < checkpointBars.length; c++) {
                    snapshots[c] = snapshot(session, checkpointBars[c], daily, atr, averageVolume(cumulativeVolumes, c));
                }
                int lastCheckpoint = checkpointBars[checkpointBars.length - 1];
                double previousClose = daily.get(daily.size() - 1)[2];
                float[] path = new float[lastCheckpoint + 1];
                float[] closes = new float[exitBar + 1];
                double[] highs = new double[exitBar + 1];
                double[] lows = new double[exitBar + 1];
                for (Bar b : session) {
                    if (b.index() <= exitBar) {
                        closes[b.index()] = (float) b.close();
                        highs[b.index()] = b.high();
                        lows[b.index()] = b.low();
                    }
                    if (b.index() == 0) {
                        path[0] = (float) (b.open() / previousClose - 1.0);
                    }
                    if (b.index() < lastCheckpoint) {
                        path[b.index() + 1] = (float) (b.close() / previousClose - 1.0);
                    }
                }
                double[] laterHigh = new double[checkpointBars.length];
                double[] laterLow = new double[checkpointBars.length];
                for (int c = 0; c < checkpointBars.length; c++) {
                    laterHigh[c] = Arrays.stream(highs, checkpointBars[c], exitBar + 1).max().orElseThrow();
                    laterLow[c] = Arrays.stream(lows, checkpointBars[c], exitBar + 1).min().orElseThrow();
                }
                out.add(new Session(instrument, dates.get(d), atr, snapshots, path, closes, laterHigh, laterLow, argMax(highs), argMin(lows)));
            }
            daily.add(new double[] {session.stream().mapToDouble(Bar::high).max().orElseThrow(), session.stream().mapToDouble(Bar::low).min().orElseThrow(),
                    session.get(session.size() - 1).close()});
            cumulativeVolumes.add(volumeAt);
        }
        return new History(out, daily, cumulativeVolumes);
    }

    /** Today's snapshot from the bars that closed by the checkpoint; empty without 15 earlier sessions or with a bar missing. */
    Optional<Today> today(List<Bar> barsSoFar, int checkpoint, History earlier) {
        List<double[]> daily = earlier.daily();
        List<double[]> cumulativeVolumes = earlier.cumulativeVolumes();
        int bars = checkpointBars[checkpoint];
        List<Bar> upTo = barsSoFar.stream().filter(b -> b.index() < bars).toList();
        if (daily.size() <= ATR_SESSIONS || upTo.size() != bars) {
            return Optional.empty();
        }
        double previousClose = daily.get(daily.size() - 1)[2];
        double[] path = new double[bars + 1];
        for (Bar b : upTo) {
            if (b.index() == 0) {
                path[0] = b.open() / previousClose - 1.0;
            }
            path[b.index() + 1] = b.close() / previousClose - 1.0;
        }
        return Optional.of(new Today(snapshot(upTo, bars, daily, atr(daily), averageVolume(cumulativeVolumes, checkpoint)), path));
    }

    private boolean complete(List<Bar> session) {
        int have = 0;
        for (Bar b : session) {
            if (b.index() <= exitBar) {
                have++;
            }
        }
        return have == exitBar + 1;
    }

    private double[] cumulativeVolume(List<Bar> session) {
        double[] at = new double[checkpointBars.length];
        for (Bar b : session) {
            for (int c = 0; c < checkpointBars.length; c++) {
                if (b.index() < checkpointBars[c]) {
                    at[c] += b.volume();
                }
            }
        }
        return at;
    }

    private double averageVolume(List<double[]> cumulativeVolumes, int checkpoint) {
        int n = Math.min(props.session().volumeSessions(), cumulativeVolumes.size());
        double sum = 0;
        for (int k = cumulativeVolumes.size() - n; k < cumulativeVolumes.size(); k++) {
            sum += cumulativeVolumes.get(k)[checkpoint];
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Simple mean of the true range of the last 14 sessions. */
    private static double atr(List<double[]> daily) {
        double sum = 0;
        for (int k = daily.size() - ATR_SESSIONS; k < daily.size(); k++) {
            double previousClose = daily.get(k - 1)[2];
            sum += Math.max(daily.get(k)[0], previousClose) - Math.min(daily.get(k)[1], previousClose);
        }
        return sum / ATR_SESSIONS;
    }

    private Snapshot snapshot(List<Bar> session, int bars, List<double[]> daily, double atr, double averageVolume) {
        double previousClose = daily.get(daily.size() - 1)[2];
        double high = -Double.MAX_VALUE;
        double low = Double.MAX_VALUE;
        double rangeHigh = -Double.MAX_VALUE;
        double rangeLow = Double.MAX_VALUE;
        double peak = -Double.MAX_VALUE;
        double drawdown = 0;
        double volume = 0;
        double last = previousClose;
        for (Bar b : session) {
            if (b.index() >= bars) {
                continue;
            }
            high = Math.max(high, b.high());
            low = Math.min(low, b.low());
            if (b.index() < OPENING_RANGE_BARS) {
                rangeHigh = Math.max(rangeHigh, b.high());
                rangeLow = Math.min(rangeLow, b.low());
            }
            peak = Math.max(peak, b.high());
            drawdown = Math.max(drawdown, peak - b.low());
            volume += b.volume();
            last = b.close();
        }
        double openingRange = rangeHigh - rangeLow;
        double position = openingRange <= 0 ? 0.5 : Math.max(-2, Math.min(3, (last - rangeLow) / openingRange));
        double volumeRatio = averageVolume <= 0 || volume <= 0 ? 0 : Math.max(-2, Math.min(2, Math.log(volume / averageVolume)));
        double rangeFrom = Double.MAX_VALUE;
        double rangeTo = -Double.MAX_VALUE;
        for (int k = Math.max(0, daily.size() - RANGE_SESSIONS); k < daily.size(); k++) {
            rangeTo = Math.max(rangeTo, daily.get(k)[0]);
            rangeFrom = Math.min(rangeFrom, daily.get(k)[1]);
        }
        double earlierClose = daily.get(daily.size() - 2)[2];
        return new Snapshot(new AnalogMath.Scalars((high - low) / atr, (last - previousClose) / atr, position, volumeRatio, drawdown / atr),
                (previousClose - earlierClose) / atr, rangeTo > rangeFrom ? (previousClose - rangeFrom) / (rangeTo - rangeFrom) : 0.5, high, low, last);
    }

    private record Scored(Session session, double correlation, double contextDistance, double similarity) {
    }

    /**
     * @param candidates past sessions of the whole intraday universe; only those strictly before {@code date} are used
     * @param expiryDays sessions that were expiry days (for the split)
     */
    Analysis analyse(Instrument benchmark, LocalDate date, int checkpoint, Today today, List<Session> candidates, List<Instrument> instruments,
            Set<LocalDate> expiryDays) {
        Snapshot own = today.snapshot();
        List<Session> eligible = candidates.stream().filter(s -> s.date().isBefore(date)).toList();
        // scales: standard deviations of the scalars over the eligible sessions at this checkpoint
        double[] sum = new double[7];
        double[] sumSq = new double[7];
        for (Session s : eligible) {
            Snapshot x = s.snapshots()[checkpoint];
            double[] v = {x.scalars().volatility(), x.scalars().trend(), x.scalars().rangePosition(), x.scalars().volumeZ(), x.scalars().maxDrawdown(),
                    x.priorReturn(), x.dailyRange()};
            for (int k = 0; k < 7; k++) {
                sum[k] += v[k];
                sumSq[k] += v[k] * v[k];
            }
        }
        long n = eligible.size();
        AnalogMath.Scales scales = new AnalogMath.Scales(AnalogMath.sd(sum[0], sumSq[0], n, 1e-3), AnalogMath.sd(sum[1], sumSq[1], n, 1e-3),
                AnalogMath.sd(sum[2], sumSq[2], n, 1e-3), AnalogMath.sd(sum[3], sumSq[3], n, 1e-3), AnalogMath.sd(sum[4], sumSq[4], n, 1e-3));
        double priorScale = AnalogMath.sd(sum[5], sumSq[5], n, 1e-3);
        double rangeScale = AnalogMath.sd(sum[6], sumSq[6], n, 1e-3);
        double[] z = AnalogMath.zPath(today.path(), today.path().length - 1, today.path().length);

        List<Scored> scored = new ArrayList<>();
        if (z != null) {
            for (Session s : eligible) {
                Snapshot x = s.snapshots()[checkpoint];
                double correlation = AnalogMath.correlation(z, s.path(), z.length - 1);
                if (Double.isNaN(correlation)) {
                    continue;
                }
                double context = (Math.abs(own.priorReturn() - x.priorReturn()) / priorScale + Math.abs(own.dailyRange() - x.dailyRange()) / rangeScale) / 2.0;
                double similarity = AnalogMath.similarity(correlation, own.scalars(), x.scalars(), scales, props.weights())
                        + props.session().contextWeight() * context;
                if (similarity <= props.maxDistance()) {
                    scored.add(new Scored(s, correlation, context, similarity));
                }
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::similarity).thenComparing(c -> c.session().date()).thenComparingInt(c -> c.session().instrument()));
        List<Scored> kept = scored.subList(0, Math.min(props.maxMatches(), scored.size()));

        int from = checkpointBars[checkpoint];
        int steps = exitBar - from + 1;
        String checkpointLabel = props.session().checkpoints().get(checkpoint);
        List<AnalogMatch> matches = new ArrayList<>();
        List<OutcomeStats.Followed> followed = new ArrayList<>();
        List<Double> returnsAtr = new ArrayList<>();
        List<Integer> highBars = new ArrayList<>();
        List<Integer> lowBars = new ArrayList<>();
        int highHeld = 0;
        int lowHeld = 0;
        for (Scored c : kept) {
            Session s = c.session();
            Snapshot x = s.snapshots()[checkpoint];
            double[] cumulative = new double[steps];
            for (int k = 0; k < steps; k++) {
                cumulative[k] = (s.closes()[from + k] / x.last() - 1.0) * 100.0;
            }
            boolean heldHigh = s.laterHigh()[checkpoint] <= x.high();
            boolean heldLow = s.laterLow()[checkpoint] >= x.low();
            highHeld += heldHigh ? 1 : 0;
            lowHeld += heldLow ? 1 : 0;
            highBars.add(s.highBar());
            lowBars.add(s.lowBar());
            double returnAtr = (s.closes()[exitBar] - x.last()) / s.atr();
            returnsAtr.add(returnAtr);
            Instrument instrument = instruments.get(s.instrument());
            followed.add(new OutcomeStats.Followed(instrument.symbol(), s.date(), cumulative));
            Map<String, Double> components = AnalogMath.components(c.correlation(), own.scalars(), x.scalars());
            components.put("context_distance", AnalogMath.round4(c.contextDistance()));
            Map<String, Double> scores = AnalogMath.scores(c.correlation(), own.scalars(), x.scalars(), scales);
            Map<String, Double> returns = new LinkedHashMap<>();
            returns.put("close", AnalogMath.round4(cumulative[steps - 1]));
            returns.put("closeAtr", AnalogMath.round4(returnAtr));
            returns.put("highHeld", heldHigh ? 1.0 : 0.0);
            returns.put("lowHeld", heldLow ? 1.0 : 0.0);
            matches.add(new AnalogMatch(instrument.id(), instrument.symbol(), s.date(), AnalogMath.round4(c.similarity()),
                    AnalogMath.round2(scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0)), components, scores, returns,
                    sparkline(s.path(), from + 1)));
        }
        double medianQuality = matches.isEmpty() ? 0 : AnalogMath.round2(AnalogMath.median(matches.stream().mapToDouble(AnalogMatch::quality).toArray()));
        String qualityTag = matches.isEmpty() ? "NONE" : medianQuality >= 4 ? "STRONG" : medianQuality >= 3 ? "MODERATE" : "WEAK";
        AnalogSummary.Outcome outcome = OutcomeStats.outcome("close", steps, props.session().percentScale(), followed, props);
        List<AnalogSummary.Split> splits = List.of(
                OutcomeStats.split("sameWeekday", "close", steps, followed, f -> f.date().getDayOfWeek() == date.getDayOfWeek()),
                OutcomeStats.split("expiryDay", "close", steps, followed, f -> expiryDays.contains(f.date())));
        AnalogSummary.SessionExtras extras = matches.isEmpty() ? new AnalogSummary.SessionExtras(0, 0, 0, null, null, 0)
                : new AnalogSummary.SessionExtras(matches.size(), highHeld, lowHeld, time(highBars), time(lowBars),
                        AnalogMath.round4(AnalogMath.median(returnsAtr.stream().mapToDouble(Double::doubleValue).toArray())));
        AnalogMath.Scalars f = own.scalars();
        List<AnalogSummary.Context> context = List.of(new AnalogSummary.Context(from, AnalogMath.round4(f.volatility()), AnalogMath.round4(f.trend()),
                AnalogMath.round4(f.rangePosition()), AnalogMath.round4(f.volumeZ()), AnalogMath.round4(f.maxDrawdown())));
        List<String> narrative = AnalogNarrative.read(outcome, qualityTag, medianQuality, "sessions at " + checkpointLabel,
                "over the rest of the session (to " + props.session().exitTime() + ")");
        return new Analysis(new AnalogSummary(date, benchmark.id(), benchmark.symbol(), AnalogKind.SESSION, from, checkpointLabel, props.engineVersion(),
                eligible.size(), eligible.size(), matches.size(), medianQuality, qualityTag, List.of(outcome), splits, context, narrative, extras), matches);
    }

    /** The first {@code points} values of a session path in percent. */
    private static List<Double> sparkline(float[] path, int points) {
        List<Double> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            out.add(AnalogMath.round4(path[i] * 100.0));
        }
        return out;
    }

    private static int argMax(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private static int argMin(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] < values[best]) {
                best = i;
            }
        }
        return best;
    }

    /** Median bar index as the time of day at which that bar opened. */
    private static String time(List<Integer> bars) {
        int median = (int) Math.round(AnalogMath.median(bars.stream().mapToDouble(Integer::doubleValue).toArray()));
        return OPEN.plusMinutes((long) median * BAR_MINUTES).toString();
    }
}
