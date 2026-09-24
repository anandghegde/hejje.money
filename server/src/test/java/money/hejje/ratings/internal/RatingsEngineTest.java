package money.hejje.ratings.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.ratings.DailyRating;
import money.hejje.ratings.RatingsProperties;
import money.hejje.ratings.RatingsService;
import org.junit.jupiter.api.Test;

/**
 * Golden fixture: 12 synthetic symbols x 300 sessions. Symbol k grows by a constant (0.02 + 0.01 k) % a session, so its
 * return over every horizon, and with it the RS rank, rises with k. Every close sits at a fixed position of the day's
 * range that falls with k, so the accumulation/distribution order is the reverse.
 */
class RatingsEngineTest {

    static final RatingsProperties.Formula FORMULA = new RatingsProperties.Formula(63, List.of(0.4, 0.2, 0.2, 0.2), 65, 252, 50, 3,
            new RatingsProperties.CompositeWeights(0.5, 0.2, 0.15, 0.15), 0.2);
    static final List<LocalDate> DAYS = weekdays(LocalDate.of(2024, 1, 1), 300);

    static List<LocalDate> weekdays(LocalDate from, int n) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return out;
    }

    /** {@code sessions} candles ending on the fixture's last day. */
    static RatingsEngine.Member member(int k, String industry, int sessions) {
        double growth = (0.02 + 0.01 * k) / 100.0;
        double position = 0.95 - 0.08 * k; // close location within the range: 0.95 (k=0) .. 0.07 (k=11)
        DailySeries s = new DailySeries(new long[sessions], new double[sessions], new double[sessions], new double[sessions], new double[sessions],
                new double[sessions]);
        double close = 100;
        for (int t = 0; t < sessions; t++) {
            close *= 1 + growth;
            double range = close * 0.02;
            s.day()[t] = DAYS.get(DAYS.size() - sessions + t).toEpochDay();
            s.open()[t] = close;
            s.low()[t] = close - position * range;
            s.high()[t] = close + (1 - position) * range;
            s.close()[t] = close;
            s.volume()[t] = 100_000 + 1_000 * k;
        }
        return new RatingsEngine.Member(new UUID(0, k), "NSE:S" + k, industry, s);
    }

    static List<RatingsEngine.Member> twelve() {
        List<RatingsEngine.Member> members = new ArrayList<>();
        for (int k = 0; k < 12; k++) {
            members.add(member(k, k < 4 ? "Slow" : k < 8 ? "Middle" : k < 11 ? "Fast" : "Lonely", 300));
        }
        return members;
    }

    @Test
    void goldenRanksGradesGroupsAndComposite() {
        List<DailyRating> ratings = new RatingsEngine(FORMULA, "1").compute(DAYS.get(299), twelve()).ratings();
        assertThat(ratings).extracting(DailyRating::symbol).containsExactly("NSE:S0", "NSE:S1", "NSE:S2", "NSE:S3", "NSE:S4", "NSE:S5", "NSE:S6",
                "NSE:S7", "NSE:S8", "NSE:S9", "NSE:S10", "NSE:S11");
        // rank r of 12 -> round(1 + 98 r / 11)
        assertThat(ratings).extracting(DailyRating::rsRating).containsExactly(1, 10, 19, 28, 37, 46, 54, 63, 72, 81, 90, 99);
        // percentile p = r / 11 (reversed order) -> band floor((1 - p) x 13); C+ is the band no twelfth falls into
        assertThat(ratings).extracting(DailyRating::adGrade).containsExactly("A+", "A", "A-", "B+", "B", "B-", "C", "C-", "D+", "D", "D-", "E");
        // clv = 2 x position - 1, identical every session, so the volume-weighted mean is the same number
        assertThat(ratings.get(0).adRaw()).isEqualTo(0.9);
        assertThat(ratings.get(11).adRaw()).isEqualTo(-0.86);
        // RS raw of symbol 0: 0.4 r(63) + 0.2 r(126) + 0.2 r(189) + 0.2 r(252) with r(n) = 1.0002^n - 1
        double g = 1.0002;
        double expected = 0.4 * (Math.pow(g, 63) - 1) + 0.2 * (Math.pow(g, 126) - 1) + 0.2 * (Math.pow(g, 189) - 1) + 0.2 * (Math.pow(g, 252) - 1);
        assertThat(ratings.get(0).rsRaw()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6));
        // the close is 5 % of a 2 % range below the day's high, which is also the 252-session high of a rising series
        assertThat(ratings.get(0).offHighPct()).isCloseTo(0.1 / 1.001, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(ratings.get(0).volVsAvg50Pct()).isEqualTo(0.0);
        assertThat(ratings.get(0).upDownVolRatio()).isNull(); // no down close in 50 sessions
        assertThat(ratings.get(0).changePct()).isCloseTo(0.02, org.assertj.core.data.Offset.offset(1e-6));
        // groups by median RS: Fast, Middle, Slow; a single-member industry is not ranked
        assertThat(ratings.get(9).groupRank()).isEqualTo(1);
        assertThat(ratings.get(5).groupRank()).isEqualTo(2);
        assertThat(ratings.get(2).groupRank()).isEqualTo(3);
        assertThat(ratings.get(11).groupId()).isEqualTo("lonely");
        assertThat(ratings.get(11).groupRank()).isNull();
        assertThat(ratings).allSatisfy(r -> assertThat(r.techComposite()).isBetween(1, 99));
        assertThat(ratings.get(0).evidence()).containsEntry("partial", false).containsEntry("rsQuarters", 4).containsEntry("universe", 12);
    }

    @Test
    void groupsAreRankedByMedianStrength() {
        var groups = new RatingsEngine(FORMULA, "1").compute(DAYS.get(299), twelve()).groups();
        assertThat(groups).extracting(g -> g.groupId() + ":" + g.rank() + ":" + g.members()).containsExactly("fast:1:3", "middle:2:4", "slow:3:4");
    }

    @Test
    void ratingsOfADateIgnoreLaterCandles() {
        LocalDate date = DAYS.get(279);
        List<RatingsEngine.Member> full = twelve();
        List<RatingsEngine.Member> cut = full.stream().map(m -> new RatingsEngine.Member(m.id(), m.symbol(), m.industry(), truncate(m.series(), 280))).toList();
        RatingsEngine engine = new RatingsEngine(FORMULA, "1");
        assertThat(engine.compute(date, full)).isEqualTo(engine.compute(date, cut));
        assertThat(RatingsService.hash(engine.compute(date, full).ratings())).isEqualTo(RatingsService.hash(engine.compute(date, cut).ratings()));
    }

    @Test
    void youngListingsUseTheQuartersTheyHaveAndNoneBelowOne() {
        List<RatingsEngine.Member> members = new ArrayList<>(twelve());
        members.add(member(12, "Fast", 130)); // two quarters
        members.add(member(13, "Fast", 40));  // under a quarter
        List<DailyRating> ratings = new RatingsEngine(FORMULA, "1").compute(DAYS.get(299), members).ratings();
        DailyRating young = ratings.get(12);
        assertThat(young.evidence()).containsEntry("partial", true).containsEntry("rsQuarters", 2);
        double g = 1 + (0.02 + 0.12) / 100.0;
        assertThat(young.rsRaw()).isCloseTo((0.4 * (Math.pow(g, 63) - 1) + 0.2 * (Math.pow(g, 126) - 1)) / 0.6, org.assertj.core.data.Offset.offset(1e-6));
        DailyRating newest = ratings.get(13);
        assertThat(newest.rsRaw()).isNull();
        assertThat(newest.rsRating()).isNull();
        assertThat(newest.adGrade()).isNull();
        assertThat(newest.techComposite()).isNull();
        assertThat(newest.offHighPct()).isNotNull();
    }

    @Test
    void aSymbolWithoutACandleThatDayHasNoRow() {
        List<RatingsEngine.Member> members = new ArrayList<>(twelve());
        members.set(3, new RatingsEngine.Member(members.get(3).id(), "NSE:S3", "Slow", truncate(members.get(3).series(), 250)));
        assertThat(new RatingsEngine(FORMULA, "1").compute(DAYS.get(299), members).ratings()).hasSize(11);
    }

    @Test
    void sameInputsGiveTheSameHash() {
        RatingsEngine engine = new RatingsEngine(FORMULA, "1");
        assertThat(RatingsService.hash(engine.compute(DAYS.get(299), twelve()).ratings()))
                .isEqualTo(RatingsService.hash(engine.compute(DAYS.get(299), twelve()).ratings()));
    }

    static DailySeries truncate(DailySeries s, int n) {
        return new DailySeries(java.util.Arrays.copyOf(s.day(), n), java.util.Arrays.copyOf(s.open(), n), java.util.Arrays.copyOf(s.high(), n),
                java.util.Arrays.copyOf(s.low(), n), java.util.Arrays.copyOf(s.close(), n), java.util.Arrays.copyOf(s.volume(), n));
    }
}
