package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan M11.5 through the API: the ledger's setups on stored D1 bars, a breakout on volume that gaps through its stop two
 * sessions later, filled at the open, with delivery costs; the report's type, regime and walk-forward breakdowns. D1 bars
 * of NSE:RELIANCE in 2018, a range no other IT reads.
 */
class SwingBacktestIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate FIRST = LocalDate.of(2018, 1, 1);

    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore history;
    @Autowired JdbcTemplate jdbc;
    @Autowired SwingBacktestService backtests;

    UUID baseId;

    @AfterEach
    void cleanUp() {
        if (baseId != null) {
            jdbc.update("DELETE FROM base WHERE id = ?", baseId);
        }
    }

    @Test
    void aVolumeBreakoutThatGapsThroughItsStopFillsAtTheOpenNetOfDeliveryCosts() {
        instruments.sync();
        UUID reliance = instruments.resolve("NSE:RELIANCE").map(Instrument::id).orElseThrow();
        List<Candle> bars = new ArrayList<>();
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = FIRST; days.size() < 80; d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                days.add(d);
            }
        }
        for (int i = 0; i < days.size(); i++) {
            String[] ohlc = i < 60 ? new String[] {"98", "98.5", "97.5", "98"} : i == 60 ? new String[] {"99", "101", "98.5", "100.5"}
                    : i == 61 ? new String[] {"101", "102", "100", "101"} : i == 62 ? new String[] {"90", "91", "88", "89"} : new String[] {"89", "90", "88", "89"};
            bars.add(new Candle(reliance, Timeframe.D1, days.get(i).atStartOfDay(IST).toInstant(), new BigDecimal(ohlc[0]), new BigDecimal(ohlc[1]),
                    new BigDecimal(ohlc[2]), new BigDecimal(ohlc[3]), i == 60 ? 200_000 : 100_000, 0, false));
        }
        history.write(reliance, Timeframe.D1, bars);
        baseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO base (id, instrument_id, symbol, type, engine_version, start_date, detected_date, depth_pct, base_low, pivot, buy_low, buy_high, stop, goal)
                VALUES (?, ?, 'NSE:RELIANCE', 'FLAT_BASE', '1', ?, ?, 10.0, 90.00, 100.00, 100.00, 105.00, 93.00, 120.00)
                """, baseId, reliance, days.get(20), days.get(59));
        jdbc.update("INSERT INTO base_status_history (base_id, seq, status, status_date) VALUES (?, 0, 'NEAR_PIVOT', ?)", baseId, days.get(59));

        ResponseEntity<Map> response = rest.exchange("/api/v1/swing/backtest", HttpMethod.POST, new HttpEntity<>(Map.of("from", "2018-01-01", "to", "2018-06-30",
                "symbols", List.of("NSE:RELIANCE"), "folds", 2), bearer(adminAccessToken())), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> report = response.getBody();
        assertThat(report.get("rules")).isEqualTo("SWING");
        assertThat(report.get("setups")).isEqualTo(1);
        List<Map<String, Object>> trades = (List<Map<String, Object>>) report.get("trades");
        assertThat(trades).singleElement().satisfies(t -> {
            assertThat(t.get("entryDate")).isEqualTo(days.get(60).toString());
            assertThat(t.get("reason")).isEqualTo("STOPPED");
            assertThat(t.get("gapFill")).isEqualTo(true);
            assertThat(new BigDecimal(String.valueOf(t.get("exit")))).isEqualByComparingTo("90.00");
            assertThat((Double) t.get("netR")).isLessThan((Double) t.get("grossR"));
        });
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat(summary.get("trades")).isEqualTo(1);
        assertThat(summary.get("gapFills")).isEqualTo(1);
        assertThat((List<?>) report.get("byType")).hasSize(1);
        assertThat((List<?>) report.get("byRegime")).hasSize(1);
        assertThat((List<?>) report.get("walkForward")).hasSize(1);

        SwingBacktestService.Report ledger = backtests.run(new SwingBacktestService.Request(LocalDate.parse("2018-01-01"), LocalDate.parse("2018-06-30"),
                List.of("NSE:RELIANCE"), null, SwingBacktestService.Rules.LEDGER, null, null, null, null, null, null));
        // the ledger's rules: the same setup, the same gap fill at the open
        assertThat(ledger.trades()).singleElement().satisfies(t -> {
            assertThat(t.exit()).isEqualByComparingTo("90.00");
            assertThat(t.grossR()).isEqualTo(-1.43);
        });
    }
}
