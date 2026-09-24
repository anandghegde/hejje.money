package money.hejje.market;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;

/**
 * Store contents versus the holiday calendar for one instrument and timeframe (docs/data.md, "Integrity report").
 *
 * @param missingSessions  trading days with no candles (first 50)
 * @param shortSessions    sessions with fewer bars than expected (first 50)
 * @param syntheticCandles candles built for minutes without ticks
 * @param suspectGaps      D1 only: sessions whose open is 35 % or more away from the previous close while the index
 *                         gapped less than 5 %: most likely a split or bonus the broker has not adjusted (first 50)
 */
public record DataIntegrityReport(UUID instrumentId, Timeframe timeframe, LocalDate from, LocalDate to, int sessionsExpected,
        int sessionsWithData, List<LocalDate> missingSessions, int expectedBarsPerSession, List<SessionBars> shortSessions,
        long totalCandles, long syntheticCandles, List<SuspectGap> suspectGaps) {

    public record SessionBars(LocalDate session, int bars) {}

    public record SuspectGap(LocalDate session, java.math.BigDecimal previousClose, java.math.BigDecimal open, double gapPct) {}

    public static int expectedBars(Timeframe timeframe) {
        return switch (timeframe) {
            case M1 -> 375;
            case M3 -> 125;
            case M5 -> 75;
            case M15 -> 25;
            case H1 -> 7;
            case D1 -> 1;
        };
    }

    public double missingSessionPct() {
        return sessionsExpected == 0 ? 0 : (double) (sessionsExpected - sessionsWithData) / sessionsExpected * 100.0;
    }
}
