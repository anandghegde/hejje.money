package money.hejje.analogs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import money.hejje.analogs.AnalogMatch;
import money.hejje.analogs.AnalogSummary;
import org.junit.jupiter.api.Test;

class SessionAnalogEngineTest {

    static final int BARS = 75;
    static final int CHECKPOINT = 1; // 10:15, twelve bars
    final SessionAnalogEngine engine = new SessionAnalogEngine(AnalogFixtures.props(3));

    /** A session of 75 bars from per-bar returns, opening {@code gap} away from {@code previousClose}. */
    static List<SessionAnalogEngine.Bar> session(double previousClose, double gap, double[] returns, double volume) {
        List<SessionAnalogEngine.Bar> bars = new ArrayList<>();
        double last = previousClose * (1 + gap);
        for (int i = 0; i < returns.length; i++) {
            double open = last;
            double close = open * (1 + returns[i]);
            bars.add(new SessionAnalogEngine.Bar(i, open, Math.max(open, close) * 1.0002, Math.min(open, close) * 0.9998, close, volume));
            last = close;
        }
        return bars;
    }

    static double[] returns(Random random, int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = random.nextGaussian() * 0.0012;
        }
        return out;
    }

    /** {@code sessions} random sessions of one instrument on consecutive weekdays; each session starts from the previous close. */
    static List<List<SessionAnalogEngine.Bar>> randomSessions(long seed, int sessions) {
        Random random = new Random(seed);
        List<List<SessionAnalogEngine.Bar>> out = new ArrayList<>();
        double previousClose = 1000;
        for (int d = 0; d < sessions; d++) {
            List<SessionAnalogEngine.Bar> bars = session(previousClose, random.nextGaussian() * 0.003, returns(random, BARS), 10_000);
            out.add(bars);
            previousClose = bars.get(BARS - 1).close();
        }
        return out;
    }

    static final List<LocalDate> DATES = AnalogFixtures.weekdays(LocalDate.of(2025, 1, 1), 61);
    static final LocalDate TODAY = DATES.get(60);
    static final List<SessionAnalogEngine.Instrument> INSTRUMENTS = List.of(new SessionAnalogEngine.Instrument(new UUID(0, 0), "NSE:A"),
            new SessionAnalogEngine.Instrument(new UUID(0, 1), "NSE:B"), new SessionAnalogEngine.Instrument(new UUID(0, 2), "NSE:C"));

    @Test
    void thePlantedSessionIsTheClosestMatchAndItsRestOfDayIsTheOutcome() {
        List<List<SessionAnalogEngine.Bar>> a = randomSessions(1, 61);
        List<List<SessionAnalogEngine.Bar>> b = randomSessions(2, 60);
        List<List<SessionAnalogEngine.Bar>> c = randomSessions(3, 60);
        // plant today's first twelve bars (and its gap) into instrument C's session 40, then let it rise 0.02 % a bar to the exit
        List<SessionAnalogEngine.Bar> today = a.get(60);
        double previousA = a.get(59).get(BARS - 1).close();
        double previousC = c.get(39).get(BARS - 1).close();
        double[] planted = new double[BARS];
        for (int i = 0; i < BARS; i++) {
            planted[i] = i < 12 ? today.get(i).close() / today.get(i).open() - 1 : 0.0002;
        }
        c.set(40, session(previousC, today.get(0).open() / previousA - 1, planted, 10_000));

        SessionAnalogEngine.History historyA = engine.history(0, DATES.subList(0, 60), a.subList(0, 60));
        List<SessionAnalogEngine.Session> candidates = new ArrayList<>(historyA.sessions());
        candidates.addAll(engine.history(1, DATES.subList(0, 60), b).sessions());
        candidates.addAll(engine.history(2, DATES.subList(0, 60), c).sessions());
        assertThat(candidates).hasSize(3 * 45); // the first fifteen sessions of an instrument only feed the ATR

        SessionAnalogEngine.Today snapshot = engine.today(today, CHECKPOINT, historyA).orElseThrow();
        SessionAnalogEngine.Analysis analysis = engine.analyse(INSTRUMENTS.get(0), TODAY, CHECKPOINT, snapshot, candidates, INSTRUMENTS, Set.of(DATES.get(40)));

        AnalogMatch best = analysis.matches().stream().min(java.util.Comparator.comparingDouble(AnalogMatch::similarity)).orElseThrow();
        assertThat(best.symbol()).isEqualTo("NSE:C");
        assertThat(best.endDate()).isEqualTo(DATES.get(40));
        assertThat(best.components().get("path_correlation")).isCloseTo(1.0, offset(1e-3));
        // 10:15 to the 15:10 bar: bars 12..70, 59 bars of +0.02 %
        assertThat(best.returns().get("close")).isCloseTo((Math.pow(1.0002, 59) - 1) * 100, offset(1e-2));
        assertThat(best.returns().get("lowHeld")).isEqualTo(1.0);  // it only rose after the checkpoint
        assertThat(best.returns().get("highHeld")).isEqualTo(0.0);
        assertThat(best.path()).hasSize(13);                        // the gap and twelve closes

        AnalogSummary s = analysis.summary();
        assertThat(s.checkpoint()).isEqualTo("10:15");
        assertThat(s.lookback()).isEqualTo(12);
        assertThat(s.candidates()).isEqualTo(135);
        assertThat(s.outcomes()).hasSize(1);
        assertThat(s.outcomes().get(0).forward()).isEqualTo("close");
        assertThat(s.outcomes().get(0).count()).isEqualTo(s.matches());
        assertThat(s.outcomes().get(0).avgPath()).hasSize(59);
        assertThat(s.session().count()).isEqualTo(s.matches());
        assertThat(s.session().highHeld()).isBetween(0, s.matches());
        assertThat(s.splits()).extracting(AnalogSummary.Split::name).containsExactly("sameWeekday", "expiryDay");
        assertThat(s.splits()).allSatisfy(x -> assertThat(x.count() + x.otherCount()).isEqualTo(s.matches()));
        assertThat(s.narrative()).hasSize(5);
        assertThat(s.narrative().get(0)).contains("sessions at 10:15").contains("price ended higher over the rest of the session (to 15:10)");
    }

    @Test
    void onlySessionsBeforeTheBenchmarkDateAreCandidates() {
        List<List<SessionAnalogEngine.Bar>> a = randomSessions(1, 61);
        SessionAnalogEngine.History all = engine.history(0, DATES, a); // includes today and would include later sessions
        SessionAnalogEngine.Today snapshot = engine.today(a.get(50), CHECKPOINT, engine.history(0, DATES.subList(0, 50), a.subList(0, 50))).orElseThrow();
        SessionAnalogEngine.Analysis analysis = engine.analyse(INSTRUMENTS.get(0), DATES.get(50), CHECKPOINT, snapshot, all.sessions(), INSTRUMENTS, Set.of());
        assertThat(analysis.summary().candidates()).isEqualTo(35); // sessions 15..49
        assertThat(analysis.matches()).allSatisfy(m -> assertThat(m.endDate()).isBefore(DATES.get(50)));
    }

    @Test
    void barsAfterTheCheckpointDoNotChangeTodaysSnapshot() {
        List<List<SessionAnalogEngine.Bar>> a = randomSessions(1, 61);
        SessionAnalogEngine.History history = engine.history(0, DATES.subList(0, 60), a.subList(0, 60));
        SessionAnalogEngine.Today whole = engine.today(a.get(60), CHECKPOINT, history).orElseThrow();
        SessionAnalogEngine.Today soFar = engine.today(a.get(60).subList(0, 12), CHECKPOINT, history).orElseThrow();
        assertThat(soFar.snapshot()).isEqualTo(whole.snapshot());
        assertThat(soFar.path()).containsExactly(whole.path());
    }

    @Test
    void aMissingBarOrTooLittleHistoryGivesNoSnapshot() {
        List<List<SessionAnalogEngine.Bar>> a = randomSessions(1, 61);
        SessionAnalogEngine.History history = engine.history(0, DATES.subList(0, 60), a.subList(0, 60));
        List<SessionAnalogEngine.Bar> gappy = new ArrayList<>(a.get(60).subList(0, 12));
        gappy.remove(5);
        assertThat(engine.today(gappy, CHECKPOINT, history)).isEmpty();
        assertThat(engine.today(a.get(60).subList(0, 6), CHECKPOINT, history)).isEmpty(); // 10:15 has not happened yet
        assertThat(engine.today(a.get(10), CHECKPOINT, engine.history(0, DATES.subList(0, 10), a.subList(0, 10)))).isEmpty();
    }

    @Test
    void checkpointsCountClosedBars() {
        assertThat(SessionAnalogEngine.barsBy(java.time.LocalTime.of(9, 45))).isEqualTo(6);
        assertThat(SessionAnalogEngine.barsBy(java.time.LocalTime.of(13, 0))).isEqualTo(45);
        assertThat(SessionAnalogEngine.barsBy(java.time.LocalTime.of(15, 10))).isEqualTo(71); // the 15:05 bar is index 70
    }
}
