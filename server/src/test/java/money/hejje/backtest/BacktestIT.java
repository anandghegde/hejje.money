package money.hejje.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.Timeframe;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class BacktestIT extends AbstractIntegrationTest {

    static final LocalDate FIRST = LocalDate.of(2026, 6, 1);   // Monday
    static final String YAML = BacktestEngineTest.ORB.replace("name: orb_test", "name: it_backtest_orb").replace("NSE:TEST", "NSE:INFY");

    @Autowired StrategyService strategies;
    @Autowired BacktestService backtests;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired JdbcTemplate jdbc;

    UUID infy;
    String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE backtest_trade, backtest, strategy_deployment, strategy_version, strategy CASCADE");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        token = adminAccessToken();
        // Mon-Wed flat (warm-up + no trade), Thu breakout to target, Fri breakout stopped out
        List<Candle> candles = new ArrayList<>();
        candles.addAll(SyntheticSessions.flat(infy, FIRST, "1500"));
        candles.addAll(SyntheticSessions.flat(infy, FIRST.plusDays(1), "1500"));
        candles.addAll(SyntheticSessions.flat(infy, FIRST.plusDays(2), "1500"));
        candles.addAll(SyntheticSessions.session(infy, FIRST.plusDays(3), List.of(
                SyntheticSessions.bar("1500", "1505", "1495", "1500"),
                SyntheticSessions.bar("1500", "1503", "1497", "1501"),
                SyntheticSessions.bar("1501", "1504", "1498", "1502"),
                SyntheticSessions.bar("1502", "1508", "1501", "1507"),
                SyntheticSessions.bar("1508", "1510", "1506", "1509"),
                SyntheticSessions.bar("1509", "1540", "1508", "1535")), 100_000));
        candles.addAll(SyntheticSessions.session(infy, FIRST.plusDays(4), List.of(
                SyntheticSessions.bar("1500", "1505", "1495", "1500"),
                SyntheticSessions.bar("1500", "1503", "1497", "1501"),
                SyntheticSessions.bar("1501", "1504", "1498", "1502"),
                SyntheticSessions.bar("1502", "1508", "1501", "1507"),
                SyntheticSessions.bar("1508", "1510", "1506", "1509"),
                SyntheticSessions.bar("1509", "1510", "1490", "1492")), 100_000));
        historical.write(infy, Timeframe.M5, candles);
    }

    private Map<String, Object> submitBody(UUID strategyId) {
        return Map.of("strategyId", strategyId.toString(), "version", 1, "instruments", List.of("NSE:INFY"),
                "from", FIRST.plusDays(3).toString(), "to", FIRST.plusDays(4).toString(), "slippageBps", 0,
                "splits", Map.of("type", "NONE"), "riskPerTradeRupees", 2000);
    }

    private Map<?, ?> awaitDone(UUID id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            ResponseEntity<Map> r = rest.exchange("/api/v1/backtests/" + id, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
            String status = (String) r.getBody().get("status");
            if ("DONE".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return r.getBody();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("backtest did not finish");
    }

    @Test
    void submitRunInspectAndUseAsEvidence() throws Exception {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        ResponseEntity<Map> accepted = rest.exchange("/api/v1/backtests", HttpMethod.POST, new HttpEntity<>(submitBody(v1.strategyId()), bearer(token)), Map.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().get("status")).isEqualTo("QUEUED");
        UUID id = UUID.fromString((String) accepted.getBody().get("id"));

        Map<?, ?> done = awaitDone(id);
        assertThat(done.get("status")).as(String.valueOf(done.get("error"))).isEqualTo("DONE");
        assertThat(done.get("progressPct")).isEqualTo(100);
        Map<?, ?> metrics = (Map<?, ?>) done.get("metrics");
        assertThat(metrics.get("totalTrades")).isEqualTo(2);
        assertThat(metrics.get("winningTrades")).isEqualTo(1);
        assertThat(metrics.get("losingTrades")).isEqualTo(1);
        assertThat(done.get("sessionsExpected")).isEqualTo(2);
        assertThat(done.get("sessionsWithData")).isEqualTo(2);
        assertThat(done.get("resultHash")).isNotNull();
        assertThat((List<Map<?, ?>>) done.get("warnings")).extracting(w -> (Object) w.get("code")).contains("INSUFFICIENT_SAMPLE");

        ResponseEntity<List> trades = rest.exchange("/api/v1/backtests/" + id + "/trades", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(trades.getBody()).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) trades.getBody().get(0);
        assertThat(first.get("exitReason")).isEqualTo("TARGET");
        assertThat(first.get("entryPrice")).isEqualTo(1508.0);
        assertThat(first.get("stop")).isEqualTo(1495.0);
        assertThat(first.get("target")).isEqualTo(1534.0);
        assertThat(((Map<?, ?>) trades.getBody().get(1)).get("exitReason")).isEqualTo("STOP");
        assertThat((List<?>) first.get("evidence")).hasSize(1);

        ResponseEntity<List> byVersion = rest.exchange("/api/v1/backtests?versionId=" + v1.id(), HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(byVersion.getBody()).hasSize(1);

        // evidence: BACKTESTED now allowed, VALIDATED still not (no out-of-sample slice / too few trades)
        ResponseEntity<Map> backtested = rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/versions/1/status", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "BACKTESTED"), bearer(token)), Map.class);
        assertThat(backtested.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(backtested.getBody().get("status")).isEqualTo("BACKTESTED");
        ResponseEntity<Map> validated = rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/versions/1/status", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "VALIDATED"), bearer(token)), Map.class);
        assertThat(validated.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(strategies.version(v1.strategyId(), 1).orElseThrow().status()).isEqualTo(VersionStatus.BACKTESTED);

        // deleting a finished backtest removes it and its trades
        assertThat(rest.exchange("/api/v1/backtests/" + id, HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/v1/backtests/" + id, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backtest_trade WHERE backtest_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void aSessionFilteredRunIsResearchOnlyAndNeverTheVersionsEvidence() throws Exception {
        StrategyVersion v1 = strategies.create(YAML.replace("name: ", "name: filtered_"), null, "admin");
        // only the first of the two sessions may be entered: the TARGET trade stays, the STOP trade of the second session is gone
        Map<String, Object> body = new java.util.HashMap<>(submitBody(v1.strategyId()));
        body.put("sessionFilter", Map.of("unlistedDates", "BLOCK",
                "days", Map.of(FIRST.plusDays(3).toString(), Map.of("instruments", List.of("NSE:INFY"), "sides", List.of("BUY")))));
        ResponseEntity<Map> accepted = rest.exchange("/api/v1/backtests", HttpMethod.POST, new HttpEntity<>(body, bearer(token)), Map.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<?, ?> done = awaitDone(UUID.fromString((String) accepted.getBody().get("id")));
        assertThat(done.get("status")).as(String.valueOf(done.get("error"))).isEqualTo("DONE");
        assertThat(((Map<?, ?>) done.get("metrics")).get("totalTrades")).isEqualTo(1);
        assertThat(((Map<?, ?>) done.get("spec")).get("sessionFilter")).isNotNull(); // recorded with the run

        // a filtered run is not the backtest the version is judged by: no evidence, so BACKTESTED is still refused
        assertThat(backtests.baseBacktest(v1.id())).isEmpty();
        ResponseEntity<Map> refused = rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/versions/1/status", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "BACKTESTED"), bearer(token)), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> bad = rest.exchange("/api/v1/backtests", HttpMethod.POST, new HttpEntity<>(Map.of("strategyId", v1.strategyId().toString(),
                "version", 1, "from", FIRST.toString(), "to", FIRST.plusDays(4).toString(), "sessionFilter", Map.of("unlistedDates", "MAYBE")), bearer(token)), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sameSpecTwiceYieldsSameHash() {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        BacktestSpec spec = new BacktestSpec(v1.id(), List.of(infy), null, FIRST.plusDays(3), FIRST.plusDays(4), FillModel.NEXT_OPEN, 5, null,
                Splits.DEFAULT_FIXED, null, null);
        Backtest a = backtests.runNow(spec, "test");
        Backtest b = backtests.runNow(spec, "test");
        assertThat(a.status()).as(a.error()).isEqualTo(BacktestStatus.DONE);
        assertThat(a.resultHash()).isEqualTo(b.resultHash());
        assertThat(a.metrics().totalTrades()).isEqualTo(2);
        assertThat(a.spec().timeframe()).isNull(); // the stored spec is as submitted; the timeframe comes from the definition at run time
        assertThat(backtests.trades(a.id(), null)).hasSize(2);
        // fixed 60/20/20 over two sessions: session 1 in-sample, session 2 out-of-sample
        assertThat(backtests.trades(a.id(), Split.OUT_OF_SAMPLE)).hasSize(1);
    }

    @Test
    void badSpecsAreRejected() {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        ResponseEntity<Map> noData = rest.exchange("/api/v1/backtests", HttpMethod.POST, new HttpEntity<>(Map.of("versionId", v1.id().toString(),
                "instruments", List.of("NSE:INFY"), "from", "2020-01-01", "to", "2020-01-31"), bearer(token)), Map.class);
        assertThat(noData.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ResponseEntity<Map> badRange = rest.exchange("/api/v1/backtests", HttpMethod.POST, new HttpEntity<>(Map.of("versionId", v1.id().toString(),
                "from", "2026-06-05", "to", "2026-06-01"), bearer(token)), Map.class);
        assertThat(badRange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> unknownVersion = rest.exchange("/api/v1/backtests", HttpMethod.POST, new HttpEntity<>(Map.of("versionId", UUID.randomUUID().toString(),
                "from", "2026-06-01", "to", "2026-06-05"), bearer(token)), Map.class);
        assertThat(unknownVersion.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
