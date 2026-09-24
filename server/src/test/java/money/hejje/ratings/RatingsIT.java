package money.hejje.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ratings end to end over the test profile's five-stock universe ({@code universe/ratings-test.yaml}). The D1 candles
 * live in 2019, a range no other IT reads (the DuckDB history is shared).
 */
class RatingsIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2019, 7, 31);
    static final List<String> SYMBOLS = List.of("NSE:RELIANCE", "NSE:INFY", "NSE:TCS", "NSE:HDFCBANK", "NSE:SBIN");

    @Autowired RatingsService ratings;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired HejjeClock hejjeClock;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    List<LocalDate> sessions;
    String token;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE daily_rating, industry_group_rank");
        instruments.sync();
        sessions = new ArrayList<>();
        for (LocalDate d = DAY; sessions.size() < 140; d = d.minusDays(1)) {
            if (hejjeClock.isTradingDay(d)) {
                sessions.add(0, d);
            }
        }
        // symbol k grows (0.05 + 0.05 k) % a session: SBIN is the strongest, RELIANCE the weakest
        for (int k = 0; k < SYMBOLS.size(); k++) {
            UUID id = instruments.resolve(SYMBOLS.get(k)).map(Instrument::id).orElseThrow();
            List<Candle> candles = new ArrayList<>();
            double close = 500;
            for (LocalDate d : sessions) {
                close *= 1 + (0.05 + 0.05 * k) / 100.0;
                candles.add(new Candle(id, Timeframe.D1, d.atStartOfDay(IST).toInstant(), dec(close), dec(close * 1.01), dec(close * 0.99), dec(close),
                        100_000, 0, false));
            }
            historical.write(id, Timeframe.D1, candles);
        }
        clock.setIst(DAY + "T19:00:00");
        token = adminAccessToken();
    }

    @AfterEach
    void resetClock() {
        clock.set(java.time.Instant.now());
    }

    @Test
    void computingIsReproducibleAndARerunIsANoOp() {
        LocalDate from = sessions.get(100);
        RatingsComputeResult first = ratings.compute(from, DAY);
        assertThat(first.sessions()).isEqualTo(40);
        assertThat(first.computed()).isEqualTo(40);
        assertThat(first.rows()).isEqualTo(200);

        RatingsComputeResult again = ratings.compute(from, DAY);
        assertThat(again.computed()).isZero();
        assertThat(again.hash()).isEqualTo(first.hash());

        jdbc.execute("TRUNCATE daily_rating, industry_group_rank");
        assertThat(ratings.compute(from, DAY).hash()).isEqualTo(first.hash());
    }

    @Test
    void apiListsTheSessionSortedAndCapsTheDateAtTheLastClosedSession() {
        ratings.compute(sessions.get(130), DAY);

        ResponseEntity<Map> list = rest.exchange("/api/v1/ratings?sort=rs&limit=3", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("date")).isEqualTo(DAY.toString());
        assertThat(list.getBody().get("universe")).isEqualTo(5);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.getBody().get("ratings");
        assertThat(rows).extracting(r -> r.get("symbol")).containsExactly("NSE:SBIN", "NSE:HDFCBANK", "NSE:TCS");
        assertThat(rows.get(0).get("rsRating")).isEqualTo(99);
        assertThat(rows.get(0).get("groupId")).isEqualTo("financial-services");

        ResponseEntity<Map> one = rest.exchange("/api/v1/ratings/NSE:INFY?date=2030-01-01", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(one.getBody().get("sessionDate")).isEqualTo(DAY.toString());
        assertThat(one.getBody().get("rsRating")).isEqualTo(26); // second of five: round(1 + 98 / 4)
        assertThat((Map<String, Object>) one.getBody().get("evidence")).containsEntry("partial", true).containsEntry("rsQuarters", 2);

        ResponseEntity<List> history = rest.exchange("/api/v1/ratings/NSE:INFY/history?from=" + sessions.get(135) + "&to=" + DAY, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), List.class);
        assertThat(history.getBody()).hasSize(5);

        // before the close the session's own row is invisible: it is a function of a close that has not happened yet
        clock.setIst(DAY + "T10:00:00");
        assertThat(ratings.rating("NSE:INFY", null).orElseThrow().sessionDate()).isEqualTo(sessions.get(138));
        assertThat(ratings.history("NSE:INFY", sessions.get(135), DAY)).hasSize(4);
    }

    @Test
    void computeOverTheApiNeedsAValidRange() {
        ResponseEntity<Map> ok = rest.exchange("/api/v1/ratings/compute", HttpMethod.POST,
                new HttpEntity<>(Map.of("from", sessions.get(138).toString(), "to", DAY.toString()), bearer(token)), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("computed")).isEqualTo(2);
        assertThat((String) ok.getBody().get("hash")).hasSize(64);

        ResponseEntity<Map> bad = rest.exchange("/api/v1/ratings/compute", HttpMethod.POST,
                new HttpEntity<>(Map.of("from", DAY.toString(), "to", sessions.get(0).toString()), bearer(token)), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void universeBackfillReportsUnresolvedSymbols() throws InterruptedException {
        ResponseEntity<Map> started = rest.exchange("/api/v1/market/history/backfill-universe", HttpMethod.POST,
                new HttpEntity<>(Map.of("universe", "ratings-test", "timeframe", "D1", "from", "2018-01-01T00:00:00Z", "to", "2018-01-31T00:00:00Z"),
                        bearer(token)), Map.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getBody().get("children")).isEqualTo(6);
        assertThat((List<String>) started.getBody().get("unresolved")).containsExactly("NSE:NOSUCHSYMBOL");

        Map job = null;
        for (int i = 0; i < 100; i++) {
            job = rest.exchange("/api/v1/market/history/jobs/" + started.getBody().get("jobId"), HttpMethod.GET, new HttpEntity<>(bearer(token)),
                    Map.class).getBody();
            if (!"RUNNING".equals(job.get("status"))) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(job.get("status")).isIn("DONE", "PARTIAL");
        assertThat((Integer) job.get("childrenDone") + (Integer) job.get("childrenFailed")).isEqualTo(6);

        ResponseEntity<Map> unknown = rest.exchange("/api/v1/market/history/backfill-universe", HttpMethod.POST,
                new HttpEntity<>(Map.of("universe", "nope", "from", "2018-01-01T00:00:00Z", "to", "2018-01-31T00:00:00Z"), bearer(token)), Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
