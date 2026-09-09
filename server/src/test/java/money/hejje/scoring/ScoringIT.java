package money.hejje.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestService;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.FillModel;
import money.hejje.backtest.Splits;
import money.hejje.backtest.SyntheticSessions;
import money.hejje.common.Timeframe;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class ScoringIT extends AbstractIntegrationTest {

    static final LocalDate FIRST = LocalDate.of(2026, 7, 6); // Monday
    static final String YAML = """
            name: it_score_orb
            universe: [NSE:INFY]
            timeframe: 5m
            direction: long
            entry:
              all:
                - close > opening_range_high
            stop:
              type: opening_range_low
            target:
              type: risk_multiple
              value: 2
            trade_window:
              start: "09:30"
              end: "12:00"
            max_trades_per_day: 1
            """;

    @Autowired StrategyService strategies;
    @Autowired BacktestService backtests;
    @Autowired ScoringService scoring;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    UUID infy;
    String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE strategy_score, backtest_trade, backtest, strategy_deployment, strategy_version, strategy CASCADE");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        token = adminAccessToken();
        List<Candle> candles = new ArrayList<>();
        candles.addAll(SyntheticSessions.flat(infy, FIRST.minusDays(3), "1500"));
        // five breakout days: three winners (Mon-Wed), two losers (Thu, Fri)
        for (int i = 0; i < 5; i++) {
            boolean win = i < 3;
            candles.addAll(SyntheticSessions.session(infy, FIRST.plusDays(i), List.of(
                    SyntheticSessions.bar("1500", "1505", "1495", "1500"),
                    SyntheticSessions.bar("1500", "1503", "1497", "1501"),
                    SyntheticSessions.bar("1501", "1504", "1498", "1502"),
                    SyntheticSessions.bar("1502", "1508", "1501", "1507"),
                    SyntheticSessions.bar("1508", "1510", "1506", "1509"),
                    win ? SyntheticSessions.bar("1509", "1540", "1508", "1535") : SyntheticSessions.bar("1509", "1510", "1490", "1492")), 100_000));
        }
        historical.write(infy, Timeframe.M5, candles);
        // "now" is the evening after the last session so the technical adjuster sees these bars as recent
        clock.setIst(FIRST.plusDays(4) + "T16:00:00");
    }

    @AfterEach
    void resetClock() {
        clock.set(java.time.Instant.now());
    }

    @Test
    void scoreBreakdownSumsAndEndpointsRender() throws Exception {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        Backtest done = backtests.runNow(new BacktestSpec(v1.id(), List.of(infy), null, FIRST, FIRST.plusDays(4), FillModel.NEXT_OPEN, 5, null,
                Splits.fixed(40, 20, 40), null, null), "test");
        assertThat(done.metrics().totalTrades()).isEqualTo(5);

        ResponseEntity<List> recomputed = rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/score/recompute", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), List.class);
        assertThat(recomputed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recomputed.getBody()).hasSize(1); // one universe instrument: INFY

        ResponseEntity<Map> score = rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/score", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(score.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> breakdown = (Map<?, ?>) score.getBody().get("breakdown");
        assertThat(breakdown.get("instrumentId")).isEqualTo(infy.toString());
        assertThat(breakdown.get("baseBacktestId")).isEqualTo(done.id().toString());
        double base = ((Number) breakdown.get("base")).doubleValue();
        List<Map<?, ?>> components = (List<Map<?, ?>>) breakdown.get("components");
        assertThat(components).hasSize(7);
        double contributions = components.stream().mapToDouble(c -> ((Number) c.get("contribution")).doubleValue()).sum();
        // with only 2 out-of-sample trades the base is capped at 50 (or equals the component sum when below)
        assertThat(base).isLessThanOrEqualTo(50.0);
        assertThat(base).isCloseTo(Math.min(50.0, contributions), org.assertj.core.data.Offset.offset(0.4));
        String cap = (String) breakdown.get("cap");
        assertThat(cap == null || cap.contains("out-of-sample")).as("cap: " + cap).isTrue();
        List<Map<?, ?>> adjustments = (List<Map<?, ?>>) breakdown.get("adjustments");
        assertThat(adjustments).extracting(a -> (Object) a.get("name")).containsExactly("Technical compatibility", "Current regime", "Recent paper/live performance");
        int deltaSum = adjustments.stream().mapToInt(a -> ((Number) a.get("delta")).intValue()).sum();
        assertThat(((Number) breakdown.get("finalScore")).intValue()).isEqualTo((int) Math.max(0, Math.min(100, Math.round(base) + deltaSum)));
        // technical adjuster saw candles: the last bar closed below the opening range after the losing day -> condition fails
        Map<?, ?> technical = adjustments.get(0);
        assertThat(((Number) technical.get("delta")).intValue()).isBetween(-10, 8);
        assertThat((List<String>) technical.get("evidence")).anySatisfy(e -> assertThat(e).contains("entry conditions pass"));
        Map<?, ?> recent = adjustments.get(2);
        assertThat(((Number) recent.get("delta")).intValue()).isZero();
        assertThat((List<String>) recent.get("evidence")).anySatisfy(e -> assertThat(e).contains("no paper/live round trips"));
        // slippage sensitivity was evaluated (2x slippage re-run) and recorded
        Map<?, ?> slippage = components.get(6);
        assertThat((Map<String, Object>) slippage.get("evidence")).containsKeys("baseExpectancyR", "doubledSlippageExpectancyR", "dropPct");

        // comparison table
        ResponseEntity<List> compare = rest.exchange("/api/v1/strategies/compare?versionIds=" + v1.id(), HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        Map<?, ?> row = (Map<?, ?>) compare.getBody().get(0);
        assertThat(row.get("slug")).isEqualTo("it_score_orb");
        assertThat(row.get("trades")).isEqualTo(5);
        assertThat(row.get("hejjeScore")).isEqualTo(((Number) breakdown.get("finalScore")).intValue());
        assertThat((String) row.get("similarRegimePerformance")).contains("current regime unknown"); // no index history seeded here

        // a second version with a 3R target changes nothing on winners here but yields a valid comparison and verdict
        StrategyVersion v2 = strategies.addVersion(v1.strategyId(), YAML.replace("value: 2", "value: 1"), "1R target", "admin");
        backtests.runNow(new BacktestSpec(v2.id(), List.of(infy), null, FIRST, FIRST.plusDays(4), FillModel.NEXT_OPEN, 5, null, Splits.fixed(40, 20, 40), null, null), "test");
        ResponseEntity<Map> versions = rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/versions/compare?a=1&b=2", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(versions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) versions.getBody().get("a")).get("version")).isEqualTo(1);
        assertThat(((Map<?, ?>) versions.getBody().get("b")).get("version")).isEqualTo(2);
        assertThat((String) versions.getBody().get("verdict")).startsWith("v2 ");
        assertThat((List<?>) versions.getBody().get("deltas")).hasSize(6);
    }

    @Test
    void finishedBacktestTriggersRescoring() throws Exception {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        assertThat(scoring.latestForVersion(v1.id())).isEmpty();
        Backtest queued = backtests.submit(new BacktestSpec(v1.id(), List.of(infy), null, FIRST, FIRST.plusDays(4), FillModel.NEXT_OPEN, 5, null,
                Splits.NONE, null, null), "test");
        for (int i = 0; i < 100 && scoring.latestForVersion(v1.id()).isEmpty(); i++) {
            Thread.sleep(100);
        }
        List<ScoreBreakdown> scores = scoring.latestForVersion(v1.id());
        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).baseBacktestId()).isEqualTo(queued.id());
        assertThat(scores.get(0).base()).isLessThanOrEqualTo(50.0); // NONE split: 0 out-of-sample trades, so never above 50
        assertThat(scoring.headline(v1.id())).contains(scores.get(0).finalScore());
    }

    @Test
    void unscoredVersionHasNoBreakdown() {
        StrategyVersion v1 = strategies.create(YAML, null, "admin");
        ResponseEntity<Map> score = rest.exchange("/api/v1/strategies/" + v1.strategyId() + "/score", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(score.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(score.getBody().get("breakdown")).isNull();
        assertThat((List<?>) score.getBody().get("instruments")).isEmpty();
        List<ScoreBreakdown> computed = scoring.recomputeVersion(v1.id());
        assertThat(computed.get(0).base()).isEqualTo(0.0);
        assertThat(computed.get(0).cap()).contains("no completed backtest");
    }
}
