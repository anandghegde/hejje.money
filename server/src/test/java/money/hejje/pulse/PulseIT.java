package money.hejje.pulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.backtest.SyntheticSessions;
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
 * Pulse over seeded M5 data for the index, VIX, two sector indices and the nearest future, on a 2024 session that no
 * other integration test (or the regime engine's lookback) touches.
 */
class PulseIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2024, 6, 11); // Tuesday

    @Autowired PulseService pulse;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired HejjeClock hejjeClock;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    String token;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE market_pulse");
        instruments.sync();
        token = adminAccessToken();
        List<LocalDate> prior = new ArrayList<>();
        for (LocalDate d = DAY.minusDays(1); prior.size() < 10; d = d.minusDays(1)) {
            if (hejjeClock.isTradingDay(d)) {
                prior.add(0, d);
            }
        }
        LocalDate yesterday = prior.get(prior.size() - 1);
        seed("INDEX:NIFTY 50", yesterday, "20000", 20_050, 5, 0);           // +1.0 % after 30 bars
        seed("INDEX:INDIA VIX", yesterday, "14", 13.8, -0.02, 0);          // −2.9 % on the day
        seed("INDEX:NIFTY BANK", yesterday, "45000", 45_000, 30, 0);       // +2.0 %: strong vs the index
        seed("INDEX:NIFTY FIN SERVICE", yesterday, "21000", 21_000, -3.5, 0); // −0.5 %: weak
        UUID future = instruments.nearestFuture("NIFTY", DAY).map(Instrument::id).orElseThrow();
        List<Candle> fut = new ArrayList<>();
        for (LocalDate d : prior) {
            fut.addAll(SyntheticSessions.flat(future, d, "20040")); // 10,000 per bar
        }
        fut.addAll(SyntheticSessions.session(future, DAY, rising(20_090, 5), 20_000)); // twice the usual volume
        historical.write(future, Timeframe.M5, fut);
        clock.setIst(DAY + "T11:46:00"); // 30 closed bars
    }

    @AfterEach
    void resetClock() {
        clock.set(java.time.Instant.now());
    }

    @Test
    void pulseEndpointCompositeSectorsAndStorage() {
        PulseSnapshot s = pulse.snapshotNow(true);
        assertThat(s.date()).isEqualTo(DAY);
        TechnicalPulse t = s.technical();
        assertThat(t.direction()).isEqualTo(PulseDirection.BULLISH);
        assertThat(t.score()).isGreaterThan(20);
        Map<String, PulseComponent> byName = new java.util.HashMap<>();
        t.components().forEach(c -> byName.put(c.name(), c));
        assertThat(byName.get("index_trend").available()).isFalse(); // no daily history: regime trend unknown
        assertThat(byName.get("day_change").value()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(byName.get("relative_volume").value()).isEqualTo(1.0);
        assertThat(byName.get("relative_volume").evidence()).contains("2.00x");
        assertThat(byName.get("vix").value()).isGreaterThan(0.4);
        assertThat(byName.get("futures_basis").available()).isTrue();
        assertThat(byName.get("sectors").value()).isZero(); // one strong, one weak
        assertThat(byName.get("momentum").value()).isGreaterThan(0.5);
        MarketPulse m = s.market();
        assertThat(m.regime()).isEqualTo("Unknown");
        assertThat(m.sectors()).hasSize(9);
        assertThat(m.sectors().get(0).name()).isEqualTo("Banking");
        assertThat(m.sectors().get(0).label()).isEqualTo(SectorStrength.Label.STRONG);
        assertThat(m.sectors().get(1).label()).isEqualTo(SectorStrength.Label.WEAK);
        assertThat(m.sectors().get(2).label()).isEqualTo(SectorStrength.Label.UNKNOWN); // NIFTY IT is not in the fixture
        assertThat(m.sectors().get(2).changePct()).isNull();

        ResponseEntity<Map> current = rest.exchange("/api/v1/context/pulse", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> technical = (Map<?, ?>) current.getBody().get("technical");
        assertThat(technical.get("direction")).isEqualTo("BULLISH");
        assertThat((List<?>) technical.get("evidence")).hasSize(10);
        ResponseEntity<List> history = rest.exchange("/api/v1/context/pulse/history?date=" + DAY, HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(history.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) ((Map<?, ?>) history.getBody().get(0)).get("technical")).get("score")).isEqualTo(t.score());

        ResponseEntity<Map> today = rest.exchange("/api/v1/today", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        Map<?, ?> header = (Map<?, ?>) today.getBody().get("header");
        assertThat(header.get("regime")).isEqualTo("UNKNOWN");
        assertThat(header.get("breadth")).isEqualTo("UNKNOWN");
    }

    @Test
    void neutralWithoutAnyData() {
        clock.setIst("2023-05-09T11:46:00");
        PulseSnapshot s = pulse.current();
        assertThat(s.technical().direction()).isEqualTo(PulseDirection.NEUTRAL);
        assertThat(s.technical().coverage()).isZero();
        assertThat(s.market().sectors()).allMatch(x -> x.label() == SectorStrength.Label.UNKNOWN);
    }

    void seed(String symbol, LocalDate yesterday, String flat, double open, double step, long volume) {
        UUID id = instruments.resolve(symbol).map(Instrument::id).orElseThrow();
        List<Candle> candles = new ArrayList<>(SyntheticSessions.flat(id, yesterday, flat));
        candles.addAll(SyntheticSessions.session(id, DAY, rising(open, step), volume));
        historical.write(id, Timeframe.M5, candles);
    }

    static List<SyntheticSessions.Ohlc> rising(double open, double step) {
        List<SyntheticSessions.Ohlc> bars = new ArrayList<>();
        for (int i = 0; i < SyntheticSessions.BARS; i++) {
            double o = open + i * step;
            double c = o + step;
            bars.add(SyntheticSessions.bar(fmt(o), fmt(Math.max(o, c)), fmt(Math.min(o, c)), fmt(c)));
        }
        return bars;
    }

    static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
