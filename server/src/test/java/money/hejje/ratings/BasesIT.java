package money.hejje.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * Bases end to end: the {@code lifecycle_goal} golden series (a flat base that breaks out on volume and reaches its goal)
 * as NSE:ITC's D1 candles ending 2017-12-29, a range no other IT reads.
 */
class BasesIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate END = LocalDate.of(2017, 12, 29);

    @Autowired RatingsService ratings;
    @Autowired DailyDigest digest;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired HejjeClock hejjeClock;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    List<LocalDate> sessions;
    String token;

    @BeforeEach
    void seed() throws Exception {
        jdbc.execute("TRUNCATE base, base_status_history, base_progress, daily_rating, industry_group_rank");
        instruments.sync();
        UUID itc = instruments.resolve("NSE:ITC").map(Instrument::id).orElseThrow();
        List<String[]> rows;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/bases/lifecycle_goal.csv"), StandardCharsets.UTF_8))) {
            rows = in.lines().skip(1).map(l -> l.split(",")).toList();
        }
        sessions = new ArrayList<>();
        for (LocalDate d = END; sessions.size() < rows.size(); d = d.minusDays(1)) {
            if (hejjeClock.isTradingDay(d)) {
                sessions.add(0, d);
            }
        }
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            candles.add(new Candle(itc, Timeframe.D1, sessions.get(i).atStartOfDay(IST).toInstant(), new BigDecimal(r[0]), new BigDecimal(r[1]),
                    new BigDecimal(r[2]), new BigDecimal(r[3]), Long.parseLong(r[4]), 0, false));
        }
        historical.write(itc, Timeframe.D1, candles);
        clock.setIst(END + "T19:00:00");
        token = adminAccessToken();
    }

    @AfterEach
    void resetClock() {
        clock.set(java.time.Instant.now());
    }

    Base flat(List<Base> bases) {
        return bases.stream().filter(b -> b.type() == BaseType.FLAT_BASE).findFirst().orElseThrow();
    }

    @Test
    void theLedgerHoldsTheClosedSetupAndARerunChangesNothing() {
        BasesComputeResult first = ratings.computeBases(sessions.get(0), END);
        assertThat(first.detected()).isGreaterThanOrEqualTo(1);
        Base b = flat(ratings.basesOf("NSE:ITC", null));
        assertThat(b.status()).isEqualTo(BaseStatus.HIT_GOAL);
        assertThat(b.pivot()).isEqualByComparingTo("150.75");
        assertThat(b.volumeConfirmed()).isTrue();
        assertThat(b.outcomeR()).isEqualTo(2.86);
        assertThat(b.evidence()).containsKeys("leftHigh", "low", "sessions");

        BasesComputeResult again = ratings.computeBases(sessions.get(0), END);
        assertThat(again.detected()).isZero();
        assertThat(again.transitions()).isZero();
        assertThat(again.hash()).isEqualTo(first.hash());

        jdbc.execute("TRUNCATE base, base_status_history, base_progress");
        ratings.computeBases(sessions.get(0), sessions.get(120)); // in two runs: the second continues after the first
        assertThat(ratings.computeBases(sessions.get(0), END).hash()).isEqualTo(first.hash());
    }

    @Test
    void readsAreAsOfTheRequestedSessionSoALaterOutcomeIsInvisible() {
        ratings.computeBases(sessions.get(0), END);
        Base detected = flat(ratings.basesOf("NSE:ITC", null));
        LocalDate trigger = detected.triggerDate();

        Base before = flat(ratings.basesOf("NSE:ITC", trigger.minusDays(1)));
        assertThat(before.status()).isIn(BaseStatus.NEAR_PIVOT, BaseStatus.FORMING);
        assertThat(before.triggerDate()).isNull();
        assertThat(before.outcomeR()).isNull();
        assertThat(flat(ratings.basesOf("NSE:ITC", trigger)).status()).isEqualTo(BaseStatus.IN_BUY_ZONE);
        assertThat(ratings.basesOf("NSE:ITC", detected.detectedDate().minusDays(1))).noneMatch(x -> x.type() == BaseType.FLAT_BASE);

        // the clock caps reads the same way (SIM look-ahead guard)
        clock.setIst(trigger + "T10:00:00");
        assertThat(flat(ratings.basesOf("NSE:ITC", null)).triggerDate()).isNull();
    }

    @Test
    void listsAndTheApi() {
        ratings.compute(sessions.get(0), END);
        ratings.computeBases(sessions.get(0), END);
        LocalDate trigger = flat(ratings.basesOf("NSE:ITC", null)).triggerDate();

        List<SetupRow> buyZone = ratings.list("buyzone", trigger);
        assertThat(buyZone).extracting(r -> r.base().type()).contains(BaseType.FLAT_BASE);
        assertThat(buyZone.get(0).rating().symbol()).isEqualTo("NSE:ITC");
        assertThat(ratings.list("movers", trigger)).extracting(r -> r.rating().symbol()).containsExactly("NSE:ITC"); // +2.7 % on twice the volume

        ResponseEntity<Map> setups = rest.exchange("/api/v1/ratings/lists/setups?date=" + trigger, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(setups.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setups.getBody().get("date")).isEqualTo(trigger.toString());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) setups.getBody().get("rows");
        assertThat(((Map<String, Object>) rows.get(0).get("base")).get("status")).isEqualTo("IN_BUY_ZONE");

        ResponseEntity<List> inZone = rest.exchange("/api/v1/ratings/bases?status=IN_BUY_ZONE&type=FLAT_BASE&date=" + trigger, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), List.class);
        assertThat(inZone.getBody()).hasSize(1);
        ResponseEntity<List> ofSymbol = rest.exchange("/api/v1/ratings/NSE:ITC/bases", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(ofSymbol.getBody()).isNotEmpty();

        ResponseEntity<Map> past = rest.exchange("/api/v1/ratings/setups/past?from=" + sessions.get(0) + "&to=" + END, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        Map<String, Object> flatSummary = (Map<String, Object>) ((Map<String, Object>) past.getBody().get("summary")).get("FLAT_BASE");
        assertThat(flatSummary).containsEntry("count", 1).containsEntry("HIT_GOAL", 1).containsEntry("triggered", 1).containsEntry("meanR", 2.86);

        // the evening digest line names the day's buy-zone entries; the regime of 2017 is not labelled here
        assertThat(digest.publish(trigger, List.of("NSE:X 68 % of 40")))
                .isEqualTo("Market condition UNKNOWN. New in buy zone: 1 (NSE:ITC (volume)). Analog leaders: NSE:X 68 % of 40.");

        ResponseEntity<Map> unknown = rest.exchange("/api/v1/ratings/lists/nope", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
