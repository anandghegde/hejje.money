package money.hejje.analogs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.LocalDate;
import java.util.List;
import money.hejje.analogs.AnalogMatch;
import money.hejje.analogs.AnalogSummary;
import org.junit.jupiter.api.Test;

class AnalogEngineTest {

    static final int LOOKBACK = 15;
    static final int SESSIONS = 600;
    static final int PLANT_END = 320;

    /** Twenty random walks; walk 0's last 15 sessions are planted into walk 7 ending at session 320, followed by +1 % a session for 15 sessions. */
    static List<AnalogFixtures.Walk> planted() {
        List<AnalogFixtures.Walk> walks = AnalogFixtures.walks(20, SESSIONS, 42);
        AnalogFixtures.Walk benchmark = walks.get(0);
        AnalogFixtures.Walk host = walks.get(7);
        int from = SESSIONS - LOOKBACK;
        int to = PLANT_END - LOOKBACK + 1;
        double scale = host.close[to - 1] / benchmark.close[from - 1];
        for (int k = 0; k < LOOKBACK; k++) {
            host.close[to + k] = benchmark.close[from + k] * scale;
            host.volume[to + k] = benchmark.volume[from + k];
        }
        for (int k = 1; k <= 15; k++) {
            host.close[PLANT_END + k] = host.close[PLANT_END] * Math.pow(1.01, k);
        }
        double after = host.close[PLANT_END + 15];
        double before = AnalogFixtures.walks(20, SESSIONS, 42).get(7).close[PLANT_END + 15];
        for (int i = PLANT_END + 16; i < SESSIONS; i++) {
            host.close[i] *= after / before; // keep the rest of the walk continuous
        }
        return walks;
    }

    static DailyAnalogEngine.Analysis analyse(List<DailyAnalogEngine.Series> universe, LocalDate date, int benchmark) {
        DailyAnalogEngine engine = new DailyAnalogEngine(universe, AnalogFixtures.PROPS);
        DailyAnalogEngine.Prepared prepared = engine.prepare(date, engine.table(LOOKBACK));
        return engine.analyse(prepared, benchmark, engine.context(benchmark, date)).orElseThrow();
    }

    @Test
    void thePlantedWindowIsTheClosestMatchWithItsKnownForwardMove() {
        List<AnalogFixtures.Walk> walks = planted();
        LocalDate date = LocalDate.ofEpochDay(walks.get(0).day[SESSIONS - 1]);
        DailyAnalogEngine.Analysis a = analyse(walks.stream().map(AnalogFixtures.Walk::series).toList(), date, 0);

        AnalogMatch best = a.matches().stream().min(java.util.Comparator.comparingDouble(AnalogMatch::similarity)).orElseThrow();
        assertThat(best.symbol()).isEqualTo("NSE:W7");
        assertThat(best.endDate()).isEqualTo(LocalDate.ofEpochDay(walks.get(7).day[PLANT_END]));
        assertThat(best.components().get("path_correlation")).isCloseTo(1.0, offset(1e-4));
        assertThat(best.components().get("shape_distance")).isCloseTo(0.0, offset(0.02));
        assertThat(best.components().get("volatility_distance")).isCloseTo(0.0, offset(1e-6));
        assertThat(best.scores().get("shape")).isEqualTo(5.0);
        assertThat(best.returns().get("5")).isCloseTo((Math.pow(1.01, 5) - 1) * 100, offset(1e-3));
        assertThat(best.returns().get("15")).isCloseTo((Math.pow(1.01, 15) - 1) * 100, offset(1e-3));
        assertThat(best.path()).hasSize(LOOKBACK);
        assertThat(best.path().get(0)).isZero();

        AnalogSummary s = a.summary();
        assertThat(s.matches()).isEqualTo(a.matches().size()).isPositive();
        assertThat(s.compared()).isLessThan(s.candidates()); // the scalar prefilter did its work
        assertThat(s.outcomes()).extracting(AnalogSummary.Outcome::forward).containsExactly("3", "5", "10", "15");
        assertThat(s.outcomes().get(1).count()).isEqualTo(s.matches());
        assertThat(s.context()).extracting(AnalogSummary.Context::lookback).containsExactly(5, 10, 15, 20, 25, 30, 40, 50);
        assertThat(s.narrative()).hasSize(5);
        assertThat(s.splits()).hasSize(4).allSatisfy(x -> assertThat(x.count() + x.otherCount()).isEqualTo(s.matches()));
    }

    @Test
    void noMatchReachesTheBenchmarkDateAndTheBenchmarksOwnRecentWindowsAreOut() {
        List<AnalogFixtures.Walk> walks = planted();
        AnalogFixtures.Walk w0 = walks.get(0);
        LocalDate date = LocalDate.ofEpochDay(w0.day[SESSIONS - 1]);
        DailyAnalogEngine.Analysis a = analyse(walks.stream().map(AnalogFixtures.Walk::series).toList(), date, 0);
        for (AnalogMatch m : a.matches()) {
            int end = java.util.Arrays.binarySearch(w0.day, m.endDate().toEpochDay()); // every walk shares the calendar
            assertThat(end + 15).isLessThan(SESSIONS - 1);                                 // lookback and the longest forward window end before the benchmark date
            if (m.symbol().equals("NSE:W0")) {
                assertThat(end).isLessThanOrEqualTo(SESSIONS - 1 - LOOKBACK - 15);
            }
        }
    }

    @Test
    void overlappingWindowsOfOneSymbolYieldOneMatch() {
        List<AnalogFixtures.Walk> walks = planted();
        LocalDate date = LocalDate.ofEpochDay(walks.get(0).day[SESSIONS - 1]);
        List<AnalogMatch> matches = analyse(walks.stream().map(AnalogFixtures.Walk::series).toList(), date, 0).matches();
        // the windows ending a session or ten before and after the planted one are nearly as close, yet only one is kept per 2 x lookback span
        long nearPlant = matches.stream().filter(m -> m.symbol().equals("NSE:W7"))
                .filter(m -> Math.abs(m.endDate().toEpochDay() - walks.get(7).day[PLANT_END]) < 2 * LOOKBACK).count();
        assertThat(nearPlant).isEqualTo(1);
        for (AnalogMatch a : matches) {
            for (AnalogMatch b : matches) {
                if (a != b && a.symbol().equals(b.symbol())) {
                    int ia = java.util.Arrays.binarySearch(walks.get(0).day, a.endDate().toEpochDay());
                    int ib = java.util.Arrays.binarySearch(walks.get(0).day, b.endDate().toEpochDay());
                    assertThat(Math.abs(ia - ib)).isGreaterThanOrEqualTo(2 * LOOKBACK);
                }
            }
        }
    }

    @Test
    void laterCandlesChangeNothing() {
        List<AnalogFixtures.Walk> walks = planted();
        int at = 500;
        LocalDate date = LocalDate.ofEpochDay(walks.get(0).day[at]);
        DailyAnalogEngine.Analysis full = analyse(walks.stream().map(AnalogFixtures.Walk::series).toList(), date, 3);
        DailyAnalogEngine.Analysis cut = analyse(walks.stream().map(w -> w.series(at + 1)).toList(), date, 3);
        assertThat(full).isEqualTo(cut);
    }

    @Test
    void theSameInputsGiveTheSameAnalysis() {
        List<AnalogFixtures.Walk> walks = planted();
        LocalDate date = LocalDate.ofEpochDay(walks.get(0).day[SESSIONS - 1]);
        assertThat(analyse(walks.stream().map(AnalogFixtures.Walk::series).toList(), date, 0))
                .isEqualTo(analyse(walks.stream().map(AnalogFixtures.Walk::series).toList(), date, 0));
    }

    @Test
    void aSymbolWithoutTheHistoryForTheLookbackHasNoSummary() {
        List<AnalogFixtures.Walk> walks = AnalogFixtures.walks(5, 200, 1);
        DailyAnalogEngine engine = new DailyAnalogEngine(walks.stream().map(AnalogFixtures.Walk::series).toList(), AnalogFixtures.PROPS);
        LocalDate early = LocalDate.ofEpochDay(walks.get(0).day[40]); // 15 + 50 prior volume sessions are needed
        assertThat(engine.analyse(engine.prepare(early, engine.table(LOOKBACK)), 0, List.of())).isEmpty();
    }
}
