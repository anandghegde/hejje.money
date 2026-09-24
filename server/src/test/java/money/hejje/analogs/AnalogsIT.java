package money.hejje.analogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
 * Daily analogs end to end over the test profile's universe: seeded random walks as D1 candles of 2012, a range no
 * other IT reads (the DuckDB history is shared).
 */
class AnalogsIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2012, 12, 28);
    static final List<String> SYMBOLS = List.of("NSE:RELIANCE", "NSE:INFY", "NSE:TCS", "NSE:HDFCBANK", "NSE:SBIN");

    @Autowired AnalogsService analogs;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired HejjeClock hejjeClock;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    List<LocalDate> sessions;
    String token;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE analog_summary, analog_match");
        instruments.sync();
        sessions = new ArrayList<>();
        for (LocalDate d = DAY; sessions.size() < 220; d = d.minusDays(1)) {
            if (hejjeClock.isTradingDay(d)) {
                sessions.add(0, d);
            }
        }
        for (int k = 0; k < SYMBOLS.size(); k++) {
            UUID id = instruments.resolve(SYMBOLS.get(k)).map(Instrument::id).orElseThrow();
            Random random = new Random(7 + k);
            List<Candle> candles = new ArrayList<>();
            double close = 300 + 50 * k;
            for (LocalDate d : sessions) {
                close *= 1 + random.nextGaussian() * 0.012;
                candles.add(new Candle(id, Timeframe.D1, d.atStartOfDay(IST).toInstant(), dec(close), dec(close * 1.01), dec(close * 0.99), dec(close),
                        100_000 + random.nextInt(50_000), 0, false));
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
    void summariesAreWrittenOnceAndTheHashIsReproducible() {
        AnalogComputeResult first = analogs.computeDaily(List.of(DAY), Set.of(), List.of(15));
        assertThat(first.symbols()).isEqualTo(5);
        assertThat(first.summaries()).isEqualTo(5);

        AnalogComputeResult again = analogs.computeDaily(List.of(DAY), Set.of(), List.of(15));
        assertThat(again.summaries()).isZero();
        assertThat(again.hash()).isEqualTo(first.hash());

        jdbc.execute("TRUNCATE analog_summary, analog_match");
        assertThat(analogs.computeDaily(List.of(DAY), Set.of(), List.of(15)).hash()).isEqualTo(first.hash());
    }

    @Test
    void apiServesTheSummaryItsUnorderedMatchesAndTheRankedUniverse() {
        analogs.computeDaily(List.of(DAY), Set.of(), List.of(15));

        ResponseEntity<Map> summary = rest.exchange("/api/v1/analogs/NSE:INFY?lookback=15", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody().get("sessionDate")).isEqualTo(DAY.toString());
        assertThat(summary.getBody().get("kind")).isEqualTo("DAILY");
        assertThat((List<?>) summary.getBody().get("outcomes")).hasSize(4);
        assertThat((List<?>) summary.getBody().get("narrative")).hasSize(5);
        int count = (Integer) summary.getBody().get("matches");

        ResponseEntity<List> matches = rest.exchange("/api/v1/analogs/NSE:INFY/matches?lookback=15", HttpMethod.GET, new HttpEntity<>(bearer(token)),
                List.class);
        assertThat(matches.getBody()).hasSize(count);
        if (count > 0) {
            assertThat((Map<String, Object>) matches.getBody().get(0)).containsKeys("symbol", "endDate", "similarity", "quality", "components", "scores",
                    "returns", "path").doesNotContainKey("rank");
        }

        ResponseEntity<Map> rank = rest.exchange("/api/v1/analogs/rank?lookback=15&forward=5&sort=count&minCount=0", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rank.getBody().get("rows");
        assertThat(rows).hasSize(5);
        assertThat((Map<String, Object>) rows.get(0).get("outcome")).containsKeys("count", "winRate", "median", "direction", "reliability");

        // the batch form answers for the symbols that have a summary and leaves the others out (no M5 bars in 2012: none)
        ResponseEntity<Map> batch = rest.exchange("/api/v1/analogs/session?symbols=NSE:INFY,NSE:TCS", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(batch.getBody()).isEmpty();

        assertThat(rest.exchange("/api/v1/analogs/NSE:INFY?lookback=20", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/v1/analogs/rank?sort=nope", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aSessionIsReadableOnlyAfterItsClose() {
        analogs.computeDaily(List.of(sessions.get(218), DAY), Set.of("NSE:INFY"), List.of(15));
        assertThat(analogs.daily("NSE:INFY", 15, null).orElseThrow().sessionDate()).isEqualTo(DAY);
        clock.setIst(DAY + "T11:00:00");
        assertThat(analogs.daily("NSE:INFY", 15, null).orElseThrow().sessionDate()).isEqualTo(sessions.get(218));
        assertThat(analogs.daily("NSE:INFY", 15, DAY).orElseThrow().sessionDate()).isEqualTo(sessions.get(218));
    }

    static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
