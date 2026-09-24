package money.hejje.market.internal;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.market.DataIntegrityReport;

/** Compares stored candles with the holiday calendar (docs/data.md, "Integrity report"). */
public final class HistoryIntegrity {

    static final int LIST_CAP = 50;
    static final double SUSPECT_GAP_PCT = 35.0;
    static final double INDEX_MOVE_PCT = 5.0;

    private HistoryIntegrity() {
    }

    public static DataIntegrityReport report(UUID instrumentId, Timeframe timeframe, LocalDate from, LocalDate to, List<Candle> candles,
            HejjeClock clock) {
        return report(instrumentId, timeframe, from, to, candles, List.of(), clock);
    }

    /** As above, with the index's D1 candles over the same range for the suspect-gap check. */
    public static DataIntegrityReport report(UUID instrumentId, Timeframe timeframe, LocalDate from, LocalDate to, List<Candle> candles,
            List<Candle> indexCandles, HejjeClock clock) {
        ZoneId zone = clock.zone();
        Map<LocalDate, int[]> perSession = new TreeMap<>();
        long synthetic = 0;
        for (Candle c : candles) {
            LocalDate session = c.openTime().atZone(zone).toLocalDate();
            perSession.computeIfAbsent(session, k -> new int[1])[0]++;
            if (c.synthetic()) {
                synthetic++;
            }
        }
        int expected = 0;
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!clock.isTradingDay(d)) {
                continue;
            }
            expected++;
            if (!perSession.containsKey(d) && missing.size() < LIST_CAP) {
                missing.add(d);
            }
        }
        int expectedBars = DataIntegrityReport.expectedBars(timeframe);
        List<DataIntegrityReport.SessionBars> shortSessions = new ArrayList<>();
        for (Map.Entry<LocalDate, int[]> e : perSession.entrySet()) {
            if (e.getValue()[0] < expectedBars && shortSessions.size() < LIST_CAP) {
                shortSessions.add(new DataIntegrityReport.SessionBars(e.getKey(), e.getValue()[0]));
            }
        }
        int withData = (int) perSession.keySet().stream().filter(d -> !d.isBefore(from) && !d.isAfter(to) && clock.isTradingDay(d)).count();
        List<DataIntegrityReport.SuspectGap> suspect = timeframe == Timeframe.D1 ? suspectGaps(candles, indexCandles, zone) : List.of();
        return new DataIntegrityReport(instrumentId, timeframe, from, to, expected, withData, missing, expectedBars, shortSessions, candles.size(),
                synthetic, suspect);
    }

    /** Close-to-open moves of {@link #SUSPECT_GAP_PCT} or more that the index did not share (an unadjusted split or bonus). */
    static List<DataIntegrityReport.SuspectGap> suspectGaps(List<Candle> daily, List<Candle> indexDaily, ZoneId zone) {
        Map<LocalDate, Double> indexGap = new TreeMap<>();
        for (int i = 1; i < indexDaily.size(); i++) {
            indexGap.put(indexDaily.get(i).openTime().atZone(zone).toLocalDate(), gapPct(indexDaily.get(i - 1), indexDaily.get(i)));
        }
        List<DataIntegrityReport.SuspectGap> out = new ArrayList<>();
        for (int i = 1; i < daily.size() && out.size() < LIST_CAP; i++) {
            double gap = gapPct(daily.get(i - 1), daily.get(i));
            LocalDate session = daily.get(i).openTime().atZone(zone).toLocalDate();
            if (Math.abs(gap) >= SUSPECT_GAP_PCT && Math.abs(indexGap.getOrDefault(session, 0.0)) < INDEX_MOVE_PCT) {
                out.add(new DataIntegrityReport.SuspectGap(session, daily.get(i - 1).close(), daily.get(i).open(), Math.round(gap * 100.0) / 100.0));
            }
        }
        return out;
    }

    private static double gapPct(Candle previous, Candle current) {
        double close = previous.close().doubleValue();
        return close == 0 ? 0 : (current.open().doubleValue() - close) / close * 100.0;
    }
}
