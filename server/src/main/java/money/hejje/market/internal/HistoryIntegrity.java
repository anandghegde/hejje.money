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

    private HistoryIntegrity() {
    }

    public static DataIntegrityReport report(UUID instrumentId, Timeframe timeframe, LocalDate from, LocalDate to, List<Candle> candles,
            HejjeClock clock) {
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
        return new DataIntegrityReport(instrumentId, timeframe, from, to, expected, withData, missing, expectedBars, shortSessions, candles.size(), synthetic);
    }
}
