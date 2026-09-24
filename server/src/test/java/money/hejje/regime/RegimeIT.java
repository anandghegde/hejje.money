package money.hejje.regime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreBreakdown;
import money.hejje.scoring.ScoringService;
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

/**
 * End to end over synthetic index, VIX and constituent data: live snapshot, endpoints, reproducible labelling, adjuster
 * and backtest grouping. The breadth universe is the test profile's {@code regime/universe-test.yaml} (five fixture
 * equities); a per-class property would boot a second application context against the shared database.
 */
class RegimeIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2025, 3, 25); // a Tuesday well outside every other IT's data (and their 420-day regime lookback)
    static final int SESSIONS = 130;
    static final String YAML = """
            name: it_regime_orb
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
            regime_preferences:
              trending: preferred
              ranging: avoid
            """;

    @Autowired RegimeService regime;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore historical;
    @Autowired StrategyService strategies;
    @Autowired BacktestService backtests;
    @Autowired ScoringService scoring;
    @Autowired HejjeClock hejjeClock;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    UUID nifty;
    UUID vix;
    UUID infy;
    List<LocalDate> sessions;
    String token;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE market_regime, market_regime_intraday, strategy_score, backtest_trade, backtest, strategy_deployment, strategy_version, strategy CASCADE");
        instruments.sync();
        nifty = instruments.resolve("INDEX:NIFTY 50").map(Instrument::id).orElseThrow();
        vix = instruments.resolve("INDEX:INDIA VIX").map(Instrument::id).orElseThrow();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        token = adminAccessToken();
        sessions = tradingDaysEndingAt(DAY, SESSIONS);
        // index: a steady uptrend of +0.3 % a session with small alternating gaps; VIX: a gentle cycle
        List<Candle> indexDaily = new ArrayList<>();
        List<Candle> vixDaily = new ArrayList<>();
        double close = 20_000;
        for (int i = 0; i < sessions.size() - 1; i++) { // the last session has no daily bar: it is built from the intraday bars
            double open = close * (1 + 0.001 * ((i % 3) - 1));
            close = close * 1.003;
            indexDaily.add(daily(nifty, sessions.get(i), open, Math.max(open, close) + 40, Math.min(open, close) - 40, close));
            double v = 12 + (i % 10) * 0.2;
            vixDaily.add(daily(vix, sessions.get(i), v, v + 0.3, v - 0.3, v));
        }
        historical.write(nifty, Timeframe.D1, indexDaily);
        historical.write(vix, Timeframe.D1, vixDaily);
        // the last session: gap up 0.5 % and a trend day that never looks back
        double prevClose = close;
        double open = prevClose * 1.005;
        List<SyntheticSessions.Ohlc> bars = new ArrayList<>();
        double c = open;
        for (int i = 0; i < SyntheticSessions.BARS; i++) {
            double o = c;
            c = o + (i < 3 ? 10 : 9);
            bars.add(SyntheticSessions.bar(fmt(o), fmt(c + 2), fmt(o - 2), fmt(c)));
        }
        historical.write(nifty, Timeframe.M5, SyntheticSessions.session(nifty, DAY, bars, 0));
        // constituents: flat the day before, up 2 % with volume on the day
        for (String symbol : List.of("NSE:RELIANCE", "NSE:INFY", "NSE:TCS", "NSE:HDFCBANK", "NSE:SBIN")) {
            UUID id = instruments.resolve(symbol).map(Instrument::id).orElseThrow();
            List<Candle> candles = new ArrayList<>(SyntheticSessions.flat(id, sessions.get(sessions.size() - 2), "100"));
            List<SyntheticSessions.Ohlc> up = new ArrayList<>();
            for (int i = 0; i < SyntheticSessions.BARS; i++) {
                double o = 100 + i * 0.03;
                up.add(SyntheticSessions.bar(fmt(o), fmt(o + 0.05), fmt(o - 0.01), fmt(o + 0.03)));
            }
            candles.addAll(SyntheticSessions.session(id, DAY, up, 50_000));
            historical.write(id, Timeframe.M5, candles);
        }
        clock.setIst(DAY + "T15:31:00");
    }

    @AfterEach
    void resetClock() {
        clock.set(java.time.Instant.now());
    }

    @Test
    void currentSnapshotLabelsEveryDimensionWithEvidence() {
        RegimeSnapshot s = regime.current();
        assertThat(s.date()).isEqualTo(DAY);
        assertThat(s.trend()).isIn(Trend.UP, Trend.STRONG_UP);
        assertThat(s.volatility()).isNotEqualTo(Volatility.UNKNOWN);
        assertThat(s.opening()).isEqualTo(Opening.GAP_CONTINUATION);
        assertThat(s.breadth()).isEqualTo(Breadth.STRONG_POSITIVE);
        assertThat(s.intradayStructure()).isEqualTo(IntradayStructure.TREND_DAY);
        assertThat(s.eventEnvironment()).isEqualTo(EventEnvironment.NORMAL);
        assertThat(s.finalLabel()).isTrue();
        assertThat(s.classifierVersion()).isEqualTo("2");
        assertThat(s.features()).containsKeys("adx", "emaFast", "emaSlow", "vixPercentile", "gapPct", "advances", "rangeExpansion", "vwapCrosses");
        assertThat(s.evidence()).hasSize(7);
        assertThat(s.evidence().get(0)).startsWith("Trend " + s.trend());
        assertThat(s.evidence().get(3)).contains("5 advances / 0 declines").contains("5 of 5 above VWAP");

        ResponseEntity<Map> current = rest.exchange("/api/v1/context/regime", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current.getBody().get("intradayStructure")).isEqualTo("TREND_DAY");
        assertThat(current.getBody().get("date")).isEqualTo(DAY.toString());
    }

    @Test
    void intradaySnapshotIsProgressiveAndStored() {
        clock.setIst(DAY + "T10:01:00");
        RegimeSnapshot s = regime.snapshotNow(true);
        assertThat(s.finalLabel()).isFalse();
        assertThat(s.intradayStructure()).isEqualTo(IntradayStructure.TREND_DAY); // 9 bars, already trending
        assertThat(s.evidence().get(4)).contains("progressive");
        assertThat(s.features().get("intradayBars")).isEqualTo(9);
        assertThat(s.opening()).isEqualTo(Opening.GAP_CONTINUATION);
        ResponseEntity<List> stored = rest.exchange("/api/v1/context/regime/intraday?date=" + DAY, HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(stored.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) stored.getBody().get(0)).get("finalLabel")).isEqualTo(false);
        // the cache serves the same snapshot until the snapshot interval passes
        assertThat(regime.current()).isSameAs(s);
        clock.setIst(DAY + "T10:31:00");
        assertThat(regime.current().features().get("intradayBars")).isEqualTo(15);
    }

    @Test
    void historicalLabellingIsReproducibleAndServed() {
        LocalDate from = sessions.get(sessions.size() - 40);
        ResponseEntity<Map> first = rest.exchange("/api/v1/context/regime/label?from=" + from + "&to=" + DAY, HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("sessions")).isEqualTo(40);
        assertThat(first.getBody().get("labelled")).isEqualTo(40); // the last session is labelled from its intraday bars
        ResponseEntity<Map> second = rest.exchange("/api/v1/context/regime/label?from=" + from + "&to=" + DAY, HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(second.getBody().get("hash")).isEqualTo(first.getBody().get("hash"));

        ResponseEntity<List> history = rest.exchange("/api/v1/context/regime/history?from=" + from + "&to=" + DAY, HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(history.getBody()).hasSize(40);
        Map<?, ?> last = (Map<?, ?>) history.getBody().get(39);
        assertThat(last.get("date")).isEqualTo(DAY.toString());
        assertThat(last.get("finalLabel")).isEqualTo(true);
        assertThat(last.get("trend")).isIn("UP", "STRONG_UP");
        assertThat(last.get("intradayStructure")).isEqualTo("TREND_DAY");
        assertThat(last.get("breadth")).isEqualTo("UNKNOWN"); // historical breadth needs constituent daily bars, which were not seeded
        Map<?, ?> earlier = (Map<?, ?>) history.getBody().get(0);
        assertThat(earlier.get("trend")).isIn("UP", "STRONG_UP");
        assertThat(earlier.get("intradayStructure")).isEqualTo("UNKNOWN"); // no intraday bars for that session
        assertThat(regime.labels(from, DAY)).hasSize(40);
    }

    @Test
    void unknownWhenInputsAreMissing() {
        clock.setIst("2024-01-08T15:31:00"); // a Monday long before the seeded history
        RegimeSnapshot s = regime.current();
        assertThat(s.isUnknown()).isTrue();
        assertThat(s.evidence()).anySatisfy(e -> assertThat(e).contains("not enough daily bars"));
        assertThat(s.evidence()).anySatisfy(e -> assertThat(e).contains("Breadth: quotes for 0 of 5"));
        assertThat(rest.exchange("/api/v1/context/regime", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void regimeAdjusterExplainsPreferencesAndSimilarRegimePerformance() {
        StrategyVersion version = strategies.create(YAML, null, "admin");
        ScoreBreakdown noBacktest = scoring.compute(version.id(), infy);
        Adjustment a = noBacktest.adjustments().stream().filter(x -> x.name().equals("Current regime")).findFirst().orElseThrow();
        assertThat(a.delta()).isEqualTo(3);
        assertThat(a.min()).isEqualTo(-10);
        assertThat(a.max()).isEqualTo(10);
        assertThat(a.evidence()).anySatisfy(e -> assertThat(e).isEqualTo("Preferred regime 'trending' is current: +3"));
        assertThat(a.evidence()).anySatisfy(e -> assertThat(e).isEqualTo("Avoided regime 'ranging' is not current"));
        assertThat(a.evidence()).anySatisfy(e -> assertThat(e).contains("no base backtest"));

        // a backtest over labelled sessions: the breakdown groups its trades by the entry session's label
        LocalDate first = sessions.get(sessions.size() - 6);
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LocalDate day = sessions.get(sessions.size() - 6 + i);
            boolean win = i < 3;
            candles.addAll(SyntheticSessions.session(infy, day, List.of(
                    SyntheticSessions.bar("1500", "1505", "1495", "1500"), SyntheticSessions.bar("1500", "1503", "1497", "1501"),
                    SyntheticSessions.bar("1501", "1504", "1498", "1502"), SyntheticSessions.bar("1502", "1508", "1501", "1507"),
                    SyntheticSessions.bar("1508", "1510", "1506", "1509"),
                    win ? SyntheticSessions.bar("1509", "1540", "1508", "1535") : SyntheticSessions.bar("1509", "1510", "1490", "1492")), 100_000));
        }
        historical.write(infy, Timeframe.M5, candles);
        regime.labelHistory(first, sessions.get(sessions.size() - 2));
        Backtest done = backtests.runNow(new BacktestSpec(version.id(), List.of(infy), null, first, sessions.get(sessions.size() - 2), FillModel.NEXT_OPEN, 5, null,
                Splits.NONE, null, null), "test");
        assertThat(done.metrics().totalTrades()).isEqualTo(5);

        ResponseEntity<Map> view = rest.exchange("/api/v1/backtests/" + done.id(), HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody().get("id")).isEqualTo(done.id().toString());
        List<Map<?, ?>> byRegime = (List<Map<?, ?>>) view.getBody().get("byRegime");
        assertThat(byRegime).isNotEmpty();
        assertThat(byRegime.stream().mapToInt(b -> ((Number) b.get("trades")).intValue()).sum()).isEqualTo(5);
        assertThat(byRegime).allSatisfy(b -> assertThat((String) b.get("key")).doesNotContain("UNKNOWN"));
        String currentKey = regime.current().key();
        Map<?, ?> similar = (Map<?, ?>) view.getBody().get("similarRegime");
        if (byRegime.stream().anyMatch(b -> b.get("key").equals(currentKey))) {
            assertThat(similar.get("current")).isEqualTo(currentKey);
            assertThat(((Number) similar.get("overallTrades")).intValue()).isEqualTo(5);
        } else {
            assertThat(similar).isNull();
            assertThat((String) view.getBody().get("similarRegimeNote")).contains("no backtest trades in the current regime");
        }
        ResponseEntity<Map> byTrend = rest.exchange("/api/v1/backtests/" + done.id() + "/regimes?dims=trend", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat((List<String>) byTrend.getBody().get("dims")).containsExactly("trend");

        ScoreBreakdown scored = scoring.compute(version.id(), infy);
        Adjustment withBacktest = scored.adjustments().stream().filter(x -> x.name().equals("Current regime")).findFirst().orElseThrow();
        assertThat(withBacktest.evidence()).anySatisfy(e -> assertThat(e).contains("Similar"));
        assertThat(withBacktest.delta()).isBetween(-10, 10);
    }

    // --- helpers ---

    List<LocalDate> tradingDaysEndingAt(LocalDate end, int count) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate d = end;
        while (out.size() < count) {
            if (hejjeClock.isTradingDay(d)) {
                out.add(0, d);
            }
            d = d.minusDays(1);
        }
        return out;
    }

    static Candle daily(UUID instrument, LocalDate date, double open, double high, double low, double close) {
        return new Candle(instrument, Timeframe.D1, date.atStartOfDay(IST).toInstant(), dec(open), dec(high), dec(low), dec(close), 0, 0, false);
    }

    static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    static String fmt(double v) {
        return dec(v).toPlainString();
    }
}
