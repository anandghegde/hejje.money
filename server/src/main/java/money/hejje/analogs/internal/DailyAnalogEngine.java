package money.hejje.analogs.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.analogs.AnalogKind;
import money.hejje.analogs.AnalogMatch;
import money.hejje.analogs.AnalogSummary;
import money.hejje.analogs.AnalogsProperties;

/**
 * The daily analog engine (docs/analogs.md). Holds one close and one volume array per symbol, plus, per lookback, a
 * table of the scalar features of every window (a few floats per window, built once and reused for every benchmark).
 *
 * <p>No look-ahead by construction: a feature is a function of the sessions of its own window (and the 50 before it);
 * a window is a candidate for benchmark date {@code D} only when the longest forward window after it ends before
 * {@code D}; the scaling standard deviations are taken over exactly those candidates. Later sessions in the arrays are
 * never read for {@code D}.
 */
final class DailyAnalogEngine {

    static final int VOLUME_PRIOR = 50;

    /** One symbol's D1 history, oldest first. */
    record Series(UUID id, String symbol, long[] day, double[] close, double[] logClose, double[] volume) {

        static Series of(UUID id, String symbol, long[] day, double[] close, double[] volume) {
            return new Series(id, symbol, day, close, Arrays.stream(close).map(Math::log).toArray(), volume);
        }

        /** Number of sessions strictly before {@code date}. */
        int sessionsBefore(LocalDate date) {
            int i = Arrays.binarySearch(day, date.toEpochDay());
            return i >= 0 ? i : -i - 1;
        }

        int indexOf(LocalDate date) {
            int i = Arrays.binarySearch(day, date.toEpochDay());
            return i >= 0 ? i : -1;
        }
    }

    /** Scalar features of every window of one lookback: {@code [symbol][end index]}, NaN where the window is not usable. */
    record Table(int lookback, float[][] volatility, float[][] trend, float[][] rangePosition, float[][] volumeZ, float[][] maxDrawdown) {

        AnalogMath.Scalars at(int symbol, int end) {
            return new AnalogMath.Scalars(volatility[symbol][end], trend[symbol][end], rangePosition[symbol][end], volumeZ[symbol][end],
                    maxDrawdown[symbol][end]);
        }
    }

    /** The eligible candidates of one benchmark date and lookback, and the scales taken over them. */
    record Prepared(LocalDate date, Table table, int[] lastEnd, long candidates, AnalogMath.Scales scales) {
    }

    record Analysis(AnalogSummary summary, List<AnalogMatch> matches) {
    }

    private final List<Series> universe;
    private final AnalogsProperties props;
    private final int maxForward;

    DailyAnalogEngine(List<Series> universe, AnalogsProperties props) {
        this.universe = universe.stream().sorted(Comparator.comparing(Series::id)).toList();
        this.props = props;
        this.maxForward = props.forwards().stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    List<Series> universe() {
        return universe;
    }

    Table table(int lookback) {
        int n = universe.size();
        Table t = new Table(lookback, new float[n][], new float[n][], new float[n][], new float[n][], new float[n][]);
        for (int s = 0; s < n; s++) {
            Series series = universe.get(s);
            int size = series.close().length;
            float[][] columns = {new float[size], new float[size], new float[size], new float[size], new float[size]};
            for (float[] column : columns) {
                Arrays.fill(column, Float.NaN);
            }
            for (int end = lookback - 1 + VOLUME_PRIOR; end < size; end++) {
                AnalogMath.Scalars f = AnalogMath.scalars(series.logClose(), end, lookback, AnalogMath.volumeZ(series.volume(), end, lookback, VOLUME_PRIOR));
                columns[0][end] = (float) f.volatility();
                columns[1][end] = (float) f.trend();
                columns[2][end] = (float) f.rangePosition();
                columns[3][end] = (float) f.volumeZ();
                columns[4][end] = (float) f.maxDrawdown();
            }
            t.volatility()[s] = columns[0];
            t.trend()[s] = columns[1];
            t.rangePosition()[s] = columns[2];
            t.volumeZ()[s] = columns[3];
            t.maxDrawdown()[s] = columns[4];
        }
        return t;
    }

    /** The candidates of {@code date}: per symbol the last end index whose longest forward window ends before the date. */
    Prepared prepare(LocalDate date, Table table) {
        int n = universe.size();
        int[] lastEnd = new int[n];
        long count = 0;
        double[] sum = new double[5];
        double[] sumSq = new double[5];
        for (int s = 0; s < n; s++) {
            lastEnd[s] = universe.get(s).sessionsBefore(date) - 1 - maxForward;
            for (int end = table.lookback() - 1 + VOLUME_PRIOR; end <= lastEnd[s]; end++) {
                float[] values = {table.volatility()[s][end], table.trend()[s][end], table.rangePosition()[s][end], table.volumeZ()[s][end],
                        table.maxDrawdown()[s][end]};
                for (int k = 0; k < 5; k++) {
                    sum[k] += values[k];
                    sumSq[k] += (double) values[k] * values[k];
                }
                count++;
            }
        }
        return new Prepared(date, table, lastEnd, count, new AnalogMath.Scales(AnalogMath.sd(sum[0], sumSq[0], count, 1e-6),
                AnalogMath.sd(sum[1], sumSq[1], count, 1e-6), AnalogMath.sd(sum[2], sumSq[2], count, 1e-3), AnalogMath.sd(sum[3], sumSq[3], count, 1e-3),
                AnalogMath.sd(sum[4], sumSq[4], count, 1e-6)));
    }

    private record Scored(int symbol, int end, double correlation, double similarity) {
    }

    /** The evidence for the symbol at position {@code b} of {@link #universe()}; empty when it lacks the history for the lookback on that date. */
    Optional<Analysis> analyse(Prepared p, int b, List<AnalogSummary.Context> context) {
        Series benchmark = universe.get(b);
        int lookback = p.table().lookback();
        int at = benchmark.indexOf(p.date());
        if (at < lookback - 1 + VOLUME_PRIOR) {
            return Optional.empty();
        }
        double[] z = AnalogMath.zPath(benchmark.logClose(), at, lookback);
        if (z == null) {
            return Optional.empty();
        }
        AnalogMath.Scalars own = p.table().at(b, at);
        AnalogMath.Scales scales = p.scales();
        AnalogsProperties.Prefilter band = props.prefilter();
        float volatilityBand = (float) (band.volatility() * scales.volatility());
        float trendBand = (float) (band.trend() * scales.trend());
        float rangeBand = (float) (band.rangePosition() * scales.rangePosition());
        List<Scored> scored = new ArrayList<>();
        int compared = 0;
        for (int s = 0; s < universe.size(); s++) {
            // the benchmark's own recent windows are out for lookback + longest forward window sessions
            int last = s == b ? Math.min(p.lastEnd()[s], at - lookback - maxForward) : p.lastEnd()[s];
            float[] volatility = p.table().volatility()[s];
            float[] trend = p.table().trend()[s];
            float[] range = p.table().rangePosition()[s];
            double[] logClose = universe.get(s).logClose();
            for (int end = lookback - 1 + VOLUME_PRIOR; end <= last; end++) {
                if (Math.abs(volatility[end] - own.volatility()) > volatilityBand || Math.abs(trend[end] - own.trend()) > trendBand
                        || Math.abs(range[end] - own.rangePosition()) > rangeBand) {
                    continue;
                }
                compared++;
                double correlation = AnalogMath.correlation(z, logClose, end);
                if (Double.isNaN(correlation)) {
                    continue;
                }
                double similarity = AnalogMath.similarity(correlation, own, p.table().at(s, end), scales, props.weights());
                if (similarity <= props.maxDistance()) {
                    scored.add(new Scored(s, end, correlation, similarity));
                }
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::similarity).thenComparingInt(Scored::symbol).thenComparingInt(Scored::end));
        // de-overlap: one match per symbol per 2 x lookback span, best first
        List<Scored> kept = new ArrayList<>();
        for (Scored c : scored) {
            if (kept.size() >= props.maxMatches()) {
                break;
            }
            if (kept.stream().noneMatch(k -> k.symbol() == c.symbol() && Math.abs(k.end() - c.end()) < 2 * lookback)) {
                kept.add(c);
            }
        }
        List<AnalogMatch> matches = new ArrayList<>();
        List<OutcomeStats.Followed> followed = new ArrayList<>();
        for (Scored c : kept) {
            Series series = universe.get(c.symbol());
            AnalogMath.Scalars theirs = p.table().at(c.symbol(), c.end());
            double[] cumulative = new double[maxForward];
            for (int k = 1; k <= maxForward; k++) {
                cumulative[k - 1] = (series.close()[c.end() + k] / series.close()[c.end()] - 1.0) * 100.0;
            }
            LocalDate endDate = LocalDate.ofEpochDay(series.day()[c.end()]);
            followed.add(new OutcomeStats.Followed(series.symbol(), endDate, cumulative));
            Map<String, Double> scores = AnalogMath.scores(c.correlation(), own, theirs, scales);
            Map<String, Double> returns = new LinkedHashMap<>();
            for (int f : props.forwards()) {
                returns.put(String.valueOf(f), AnalogMath.round4(cumulative[f - 1]));
            }
            List<Double> path = new ArrayList<>();
            for (int i = c.end() - lookback + 1; i <= c.end(); i++) {
                path.add(AnalogMath.round4((series.close()[i] / series.close()[c.end() - lookback + 1] - 1.0) * 100.0));
            }
            matches.add(new AnalogMatch(series.id(), series.symbol(), endDate, AnalogMath.round4(c.similarity()),
                    AnalogMath.round2(scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0)),
                    AnalogMath.components(c.correlation(), own, theirs), scores, returns, path));
        }
        double medianQuality = matches.isEmpty() ? 0 : AnalogMath.round2(AnalogMath.median(matches.stream().mapToDouble(AnalogMatch::quality).toArray()));
        String qualityTag = matches.isEmpty() ? "NONE" : medianQuality >= 4 ? "STRONG" : medianQuality >= 3 ? "MODERATE" : "WEAK";
        List<AnalogSummary.Outcome> outcomes = new ArrayList<>();
        List<AnalogSummary.Split> splits = new ArrayList<>();
        int month = p.date().getMonthValue();
        for (int f : props.forwards()) {
            outcomes.add(OutcomeStats.outcome(String.valueOf(f), f, Math.sqrt(f), followed, props));
            // seasonality: the same matches, same calendar month as the benchmark date against the other months
            splits.add(OutcomeStats.split("sameMonth", String.valueOf(f), f, followed, x -> x.date().getMonthValue() == month));
        }
        AnalogSummary.Outcome told = outcomes.get(Math.max(0, props.forwards().indexOf(props.narrativeForward())));
        List<String> narrative = AnalogNarrative.read(told, qualityTag, medianQuality, lookback + "-session windows", "over the next " + told.forward() + " sessions");
        AnalogSummary summary = new AnalogSummary(p.date(), benchmark.id(), benchmark.symbol(), AnalogKind.DAILY, lookback, "", props.engineVersion(),
                (int) Math.min(Integer.MAX_VALUE, p.candidates()), compared, matches.size(), medianQuality, qualityTag, outcomes, splits, context, narrative, null);
        return Optional.of(new Analysis(summary, matches));
    }

    /** The symbol's own scalar features across the lookbacks it has the history for on a date (computed directly, no table needed). */
    List<AnalogSummary.Context> context(int b, LocalDate date) {
        Series series = universe.get(b);
        int at = series.indexOf(date);
        List<AnalogSummary.Context> out = new ArrayList<>();
        for (int lookback : props.lookbacks()) {
            if (at >= lookback - 1 + VOLUME_PRIOR) {
                AnalogMath.Scalars f = AnalogMath.scalars(series.logClose(), at, lookback, AnalogMath.volumeZ(series.volume(), at, lookback, VOLUME_PRIOR));
                out.add(new AnalogSummary.Context(lookback, AnalogMath.round6(f.volatility()), AnalogMath.round6(f.trend()),
                        AnalogMath.round4(f.rangePosition()), AnalogMath.round4(f.volumeZ()), AnalogMath.round6(f.maxDrawdown())));
            }
        }
        return out;
    }
}
