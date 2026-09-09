package money.hejje.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.Timeframe;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Continuous futures stitching and the data integrity report through the API (plan M2.4). */
class HistoryIT extends AbstractIntegrationTest {

    static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired MarketService market;

    Instrument sep;
    Instrument oct;
    String token;

    static List<Candle> session(UUID id, LocalDate day, String price, int bars) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            BigDecimal p = new BigDecimal(price);
            out.add(new Candle(id, Timeframe.M5, day.atTime(LocalTime.of(9, 15).plusMinutes(5L * i)).atZone(IST).toInstant(), p, p, p, p, 10, 0, i == 0));
        }
        return out;
    }

    @BeforeEach
    void setUp() {
        instruments.sync();
        sep = instruments.resolve("NFO:NIFTY:FUT:2026-09-29").orElseThrow();
        oct = instruments.resolve("NFO:NIFTY:FUT:2026-10-27").orElseThrow();
        token = adminAccessToken();
        List<Candle> candles = new ArrayList<>();
        // Mon 28 Sep and Tue 29 Sep (expiry) for both contracts; Wed 30 Sep only for October
        candles.addAll(session(sep.id(), LocalDate.of(2026, 9, 28), "100", 75));
        candles.addAll(session(sep.id(), LocalDate.of(2026, 9, 29), "101", 75));
        historical.write(sep.id(), Timeframe.M5, candles);
        candles = new ArrayList<>();
        candles.addAll(session(oct.id(), LocalDate.of(2026, 9, 28), "200", 75));
        candles.addAll(session(oct.id(), LocalDate.of(2026, 9, 29), "201", 75));
        candles.addAll(session(oct.id(), LocalDate.of(2026, 9, 30), "202", 40)); // short session
        historical.write(oct.id(), Timeframe.M5, candles);
    }

    @Test
    void buildsContinuousSeriesAndReportsIntegrity() {
        ResponseEntity<Map> built = rest.exchange("/api/v1/market/history/continuous", HttpMethod.POST,
                new HttpEntity<>(Map.of("underlying", "NIFTY", "timeframe", "M5"), bearer(token)), Map.class);
        assertThat(built.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(built.getBody().get("symbol")).isEqualTo("NFO:NIFTY:FUT:CONT");
        assertThat(built.getBody().get("lotSize")).isEqualTo(75);
        List<Map<?, ?>> segments = (List<Map<?, ?>>) built.getBody().get("segments");
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).get("instrumentId")).isEqualTo(sep.id().toString());
        assertThat(segments.get(0).get("to")).isEqualTo("2026-09-28");
        assertThat(segments.get(1).get("from")).isEqualTo("2026-09-29");
        UUID seriesId = UUID.fromString((String) built.getBody().get("id"));

        // the stitched candles: 28 Sep from September, 29-30 Sep from October
        List<Candle> stitched = market.candles(seriesId, Timeframe.M5, LocalDate.of(2026, 9, 28).atStartOfDay(IST).toInstant(),
                LocalDate.of(2026, 10, 1).atStartOfDay(IST).toInstant());
        assertThat(stitched).hasSize(75 + 75 + 40);
        assertThat(stitched.get(0).close()).isEqualByComparingTo("100");
        assertThat(stitched.get(75).close()).isEqualByComparingTo("201");
        assertThat(market.continuousSeriesFor("nifty")).isPresent();
        assertThat(market.continuousSeriesBySymbol("NFO:NIFTY:FUT:CONT").orElseThrow().id()).isEqualTo(seriesId);

        // rebuilding is idempotent
        ResponseEntity<Map> again = rest.exchange("/api/v1/market/history/continuous", HttpMethod.POST,
                new HttpEntity<>(Map.of("underlying", "NIFTY", "timeframe", "M5"), bearer(token)), Map.class);
        assertThat(again.getBody().get("id")).isEqualTo(seriesId.toString());
        ResponseEntity<List> listed = rest.exchange("/api/v1/market/history/continuous?underlying=NIFTY", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(listed.getBody()).hasSize(1);

        // integrity over Mon 28 Sep .. Fri 2 Oct: 5 expected sessions (2 Oct 2026 is Gandhi Jayanti, a holiday -> 4), 3 with data, one short
        ResponseEntity<Map> report = rest.exchange("/api/v1/market/history/integrity?instrumentId=" + seriesId + "&timeframe=M5&from=2026-09-28&to=2026-10-02",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = report.getBody();
        assertThat(body.get("sessionsWithData")).isEqualTo(3);
        int expected = (Integer) body.get("sessionsExpected");
        assertThat(expected).isBetween(4, 5);
        assertThat((List<Object>) body.get("missingSessions")).hasSize(expected - 3).contains("2026-10-01");
        assertThat(body.get("expectedBarsPerSession")).isEqualTo(75);
        List<Map<?, ?>> shortSessions = (List<Map<?, ?>>) body.get("shortSessions");
        assertThat(shortSessions).hasSize(1);
        assertThat(shortSessions.get(0).get("session")).isEqualTo("2026-09-30");
        assertThat(shortSessions.get(0).get("bars")).isEqualTo(40);
        assertThat(body.get("totalCandles")).isEqualTo(190);
        assertThat(body.get("syntheticCandles")).isEqualTo(3);
    }

    @Test
    void unknownUnderlyingIsRejected() {
        ResponseEntity<Map> r = rest.exchange("/api/v1/market/history/continuous", HttpMethod.POST,
                new HttpEntity<>(Map.of("underlying", "NOPE"), bearer(token)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
