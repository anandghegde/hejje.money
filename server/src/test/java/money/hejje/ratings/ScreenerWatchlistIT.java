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
 * Screener, saved screens and the watchlist over the same five-stock 2019 candles as {@link RatingsIT} (identical values,
 * so the shared DuckDB history is left as that test expects it).
 */
class ScreenerWatchlistIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired RatingsService ratings;
    @Autowired ScreenerService screener;
    @Autowired WatchlistService watchlist;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired HejjeClock hejjeClock;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    String token;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE daily_rating, industry_group_rank, base, base_status_history, base_progress, watchlist_item");
        jdbc.execute("DELETE FROM screen WHERE NOT seeded");
        instruments.sync();
        List<LocalDate> sessions = new ArrayList<>();
        for (LocalDate d = RatingsIT.DAY; sessions.size() < 140; d = d.minusDays(1)) {
            if (hejjeClock.isTradingDay(d)) {
                sessions.add(0, d);
            }
        }
        for (int k = 0; k < RatingsIT.SYMBOLS.size(); k++) {
            UUID id = instruments.resolve(RatingsIT.SYMBOLS.get(k)).map(Instrument::id).orElseThrow();
            List<Candle> candles = new ArrayList<>();
            double close = 500;
            for (LocalDate d : sessions) {
                close *= 1 + (0.05 + 0.05 * k) / 100.0;
                candles.add(new Candle(id, Timeframe.D1, d.atStartOfDay(IST).toInstant(), dec(close), dec(close * 1.01), dec(close * 0.99), dec(close),
                        100_000, 0, false));
            }
            historical.write(id, Timeframe.D1, candles);
        }
        clock.setIst(RatingsIT.DAY + "T19:00:00");
        token = adminAccessToken();
        ratings.compute(RatingsIT.DAY, RatingsIT.DAY);
    }

    @AfterEach
    void resetClock() {
        clock.set(java.time.Instant.now());
    }

    @Test
    void screenFiltersSortsAndReportsTheUniverse() {
        ScreenerService.Result strong = screener.run(new ScreenRequest(null, List.of(new ScreenRequest.Filter("rsRating", "gte", 50)), "-rsRating", null));
        assertThat(strong.date()).isEqualTo(RatingsIT.DAY.toString());
        assertThat(strong.universe()).isEqualTo(5);
        assertThat(strong.matched()).isEqualTo(3);
        assertThat(strong.rows()).extracting(r -> r.get("symbol")).containsExactly("NSE:SBIN", "NSE:HDFCBANK", "NSE:TCS");
        assertThat(strong.rows().get(0)).containsKeys("techComposite", "adGrade", "offHighPct", "baseStatus", "distanceToPivotPct", "watchlist");
        // analog fields are valid to filter on; they are on a row only when the session has daily-analog summaries
        assertThat(screener.fields()).contains("analogDirection", "analogReliability", "analogWinRate5", "analogCount5");

        ScreenerService.Result group = screener.run(new ScreenRequest(null, List.of(new ScreenRequest.Filter("groupId", "in", List.of("information-technology")),
                new ScreenRequest.Filter("changePct", "gt", 0)), "symbol", 1));
        assertThat(group.matched()).isEqualTo(2);
        assertThat(group.rows()).extracting(r -> r.get("symbol")).containsExactly("NSE:INFY");
        // a stock without a base never matches a base filter, whatever the operator
        assertThat(screener.run(new ScreenRequest(null, List.of(new ScreenRequest.Filter("baseStatus", "ne", "STOPPED")), null, null)).matched()).isZero();
    }

    @Test
    void theApiValidatesFieldsAndSavesScreens() {
        ResponseEntity<Map> bad = rest.exchange("/api/v1/ratings/screen", HttpMethod.POST,
                new HttpEntity<>(Map.of("filters", List.of(Map.of("field", "marketCap", "op", "gte", "value", 1))), bearer(token)), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<List> seeded = rest.exchange("/api/v1/ratings/screens", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat((List<Map<String, Object>>) seeded.getBody()).extracting(s -> s.get("name"))
                .contains("Leaders", "In buy zone", "Near pivot", "On the move: up", "On the move: down", "Top groups");

        Map<String, Object> definition = Map.of("filters", List.of(Map.of("field", "rsRating", "op", "gte", "value", 70)), "sort", "-rsRating", "limit", 10);
        ResponseEntity<Map> saved = rest.exchange("/api/v1/ratings/screens", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Strong RS", "definition", definition), bearer(token)), Map.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = (String) saved.getBody().get("id");
        ResponseEntity<Map> run = rest.exchange("/api/v1/ratings/screens/" + id + "/run", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(run.getBody().get("matched")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'SCREEN_SAVED'", Integer.class)).isPositive();

        assertThat(rest.exchange("/api/v1/ratings/screens/" + id, HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/v1/ratings/screens/" + id + "/run", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theWatchlistIsAuditedAndShowsUpInTheScreener() {
        ResponseEntity<Map> added = rest.exchange("/api/v1/ratings/watchlist", HttpMethod.POST,
                new HttpEntity<>(Map.of("symbol", "nse:infy", "note", "results next week"), bearer(token)), Map.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(added.getBody()).containsEntry("symbol", "NSE:INFY").containsEntry("note", "results next week");
        watchlist.add("NSE:INFY", "changed", "tester"); // adding again replaces the note
        assertThat(watchlist.items()).hasSize(1);
        assertThat(watchlist.items().get(0).note()).isEqualTo("changed");
        assertThat(watchlist.instrumentIds()).containsExactly(instruments.resolve("NSE:INFY").orElseThrow().id());

        ScreenerService.Result watched = screener.run(new ScreenRequest(null, List.of(new ScreenRequest.Filter("watchlist", "eq", "true")), null, null));
        assertThat(watched.rows()).extracting(r -> r.get("symbol")).containsExactly("NSE:INFY");

        assertThat(rest.exchange("/api/v1/ratings/watchlist", HttpMethod.POST, new HttpEntity<>(Map.of("symbol", "NSE:NOPE"), bearer(token)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/api/v1/ratings/watchlist/NSE:INFY", HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/v1/ratings/watchlist/NSE:INFY", HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'WATCHLIST_UPDATED'", Integer.class)).isGreaterThanOrEqualTo(3);
    }

    static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
