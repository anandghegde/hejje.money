package money.hejje.ratings.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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
import money.hejje.ratings.RatingsProperties;
import money.hejje.ratings.RatingsService;
import money.hejje.ratings.ScreenRequest;
import money.hejje.ratings.ScreenerService;
import money.hejje.ratings.Surveillance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NSE surveillance lists: storage by session, the flag on ratings and in the screener, staleness and a failed fetch. Over
 * the same five-stock 2019 candles as {@code RatingsIT} (identical values, so the shared DuckDB history is left as that
 * test expects it). Nothing here calls NSE: the lists are stored directly, and the failing fetch goes to a closed port.
 */
class SurveillanceIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2019, 7, 31);
    static final List<String> SYMBOLS = List.of("NSE:RELIANCE", "NSE:INFY", "NSE:TCS", "NSE:HDFCBANK", "NSE:SBIN");

    @Autowired RatingsService ratings;
    @Autowired RatingsProperties props;
    @Autowired ScreenerService screener;
    @Autowired SurveillanceStore store;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired HejjeClock hejjeClock;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE daily_rating, industry_group_rank, base, base_status_history, base_progress, watchlist_item, surveillance_snapshot CASCADE");
        instruments.sync();
        List<LocalDate> sessions = new ArrayList<>();
        for (LocalDate d = DAY; sessions.size() < 140; d = d.minusDays(1)) {
            if (hejjeClock.isTradingDay(d)) {
                sessions.add(0, d);
            }
        }
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
        ratings.compute(DAY, DAY);
    }

    @AfterEach
    void reset() {
        jdbc.execute("TRUNCATE surveillance_snapshot CASCADE");
        clock.set(Instant.now());
    }

    @Test
    void theFlagIsOnTheRatingsTheListsAndTheScreener() {
        assertThat(ratings.rating("NSE:SBIN", DAY).orElseThrow().surveillance()).as("no list fetched yet").isNull();

        store.replace(DAY, DAY, DAY, clock.instant(), List.of(new SurveillanceParser.Flag("NSE:RELIANCE", "ASM_ST_1", "STASM - I (11)")));
        // a re-fetch the same day replaces the snapshot as a whole
        store.replace(DAY, DAY, DAY, clock.instant(), List.of(new SurveillanceParser.Flag("NSE:SBIN", "GSM_2", "GSM - II (2)"),
                new SurveillanceParser.Flag("NSE:INFY", "ASM_LT_1", "LTASM - I (13)"), new SurveillanceParser.Flag("NSE:AGSTRA", "GSM_0", "GSM - 0 (99)")));
        assertThat(ratings.rating("NSE:SBIN", DAY).orElseThrow().surveillance()).isEqualTo(new Surveillance("GSM_2", "GSM - II (2)", DAY, false));
        assertThat(ratings.rating("NSE:RELIANCE", DAY).orElseThrow().surveillance()).isEqualTo(new Surveillance("NONE", null, DAY, false));

        ScreenerService.Result flagged = screener.run(new ScreenRequest(null,
                List.of(new ScreenRequest.Filter("surveillance", "in", List.of("GSM_2", "ASM_LT_1"))), "symbol", null));
        assertThat(flagged.rows()).extracting(r -> r.get("symbol")).containsExactly("NSE:INFY", "NSE:SBIN");
        assertThat(screener.run(new ScreenRequest(null, List.of(new ScreenRequest.Filter("surveillance", "eq", "NONE")), null, null)).matched()).isEqualTo(3);
        assertThat(screener.fields()).contains("surveillance");

        String token = adminAccessToken();
        ResponseEntity<Map> one = rest.exchange("/api/v1/ratings/NSE:INFY", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat((Map<String, Object>) one.getBody().get("surveillance")).containsEntry("flag", "ASM_LT_1").containsEntry("stale", false);
        // tests never call NSE: the on-demand fetch reports that it is switched off
        ResponseEntity<Map> refresh = rest.exchange("/api/v1/ratings/surveillance/refresh", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(refresh.getBody()).containsEntry("ok", false);
        assertThat((String) refresh.getBody().get("error")).startsWith("disabled");
    }

    @Test
    void aFailedFetchKeepsThePreviousListsMarkedStale() {
        LocalDate previous = DAY.minusDays(1);
        store.replace(previous, previous, previous, clock.instant(), List.of(new SurveillanceParser.Flag("NSE:SBIN", "ASM_LT_2", "LTASM - II (14)")));
        RatingsProperties.SurveillanceLists closedPort = new RatingsProperties.SurveillanceLists(true, "http://127.0.0.1:9/api/reportASM",
                "http://127.0.0.1:9/api/reportGSM");
        NseSurveillance failing = new NseSurveillance(new RatingsProperties(props.enabled(), props.universe(), props.engineVersion(),
                props.refreshSessions(), props.formula(), props.bases(), props.lists(), closedPort), store, hejjeClock, json);

        NseSurveillance.Result result = failing.refresh();
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(store.latest(DAY)).contains(previous);
        assertThat(ratings.rating("NSE:SBIN", DAY).orElseThrow().surveillance()).isEqualTo(new Surveillance("ASM_LT_2", "LTASM - II (14)", previous, true));
        assertThat(screener.run(new ScreenRequest(null, List.of(new ScreenRequest.Filter("surveillance", "eq", "ASM_LT_2")), null, null)).matched()).isEqualTo(1);
    }

    static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
