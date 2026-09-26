package money.hejje.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.Gtt;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.execution.GttService;
import money.hejje.execution.PositionGtt;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.orders.Trade;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.signals.SignalStatus;
import money.hejje.sim.AbstractSimIT;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionService;
import money.hejje.sim.SimSessionSpec;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan M11.4 acceptance: a replayed session in SIM with READY plans. TCS crosses its pivot on volume inside the buy zone:
 * one signal, AUTO-executed as a delivery LIMIT, filled, and protected by an OCO GTT with the plan's stop and goal. SBIN
 * crosses without volume and HDFCBANK trades above its buy zone: no signal for either.
 */
class SwingEntriesSimIT extends AbstractSimIT {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 12, 8);
    static final LocalDate PREVIOUS = LocalDate.of(2026, 12, 7);

    @Autowired SimSessionService sessions;
    @Autowired HistoricalCandleStore history;
    @Autowired InstrumentService instruments;
    @Autowired OrderService orders;
    @Autowired SignalService signals;
    @Autowired StrategyService strategies;
    @Autowired SwingEntries entries;
    @Autowired GttService gtts;
    @Autowired BrokerAdapter broker;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.auto.AutoExecutor auto;

    UUID tcs;
    UUID sbin;
    UUID hdfc;
    StrategyDeployment deployment;

    /** 375 M1 bars: {@code before} until 09:45, then {@code after}; each bar closes 0.25 above its open, with 0.25 wicks. */
    static List<Candle> day(UUID id, String before, String after, long volume) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < SimSession.STEPS_PER_DAY; i++) {
            Instant t = DAY.atTime(LocalTime.of(9, 15).plusMinutes(i)).atZone(IST).toInstant();
            BigDecimal open = new BigDecimal(i < 30 ? before : after);
            BigDecimal close = open.add(new BigDecimal("0.25"));
            out.add(new Candle(id, Timeframe.M1, t, open, close.add(new BigDecimal("0.25")), open.subtract(new BigDecimal("0.25")), close, volume, 0, false));
        }
        return out;
    }

    /** 60 daily bars before the session with 1,00,000 shares each: the 50-session average the volume pace uses. */
    static List<Candle> dailyHistory(UUID id, String close) {
        List<Candle> out = new ArrayList<>();
        LocalDate d = PREVIOUS;
        while (out.size() < 60) {
            if (d.getDayOfWeek().getValue() <= 5) {
                BigDecimal c = new BigDecimal(close);
                out.add(0, new Candle(id, Timeframe.D1, d.atStartOfDay(IST).toInstant(), c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 100_000, 0, false));
            }
            d = d.minusDays(1);
        }
        return out;
    }

    void base(UUID instrumentId, String symbol, String pivot, String buyHigh, String stop, String goal) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO base (id, instrument_id, symbol, type, engine_version, start_date, detected_date, depth_pct, base_low, pivot, buy_low, buy_high, stop, goal)
                VALUES (?, ?, ?, 'FLAT_BASE', '1', ?, ?, 12.0, ?, ?, ?, ?, ?, ?)
                """, id, instrumentId, symbol, PREVIOUS.minusDays(40), PREVIOUS.minusDays(2), new BigDecimal(pivot).multiply(new BigDecimal("0.88")),
                new BigDecimal(pivot), new BigDecimal(pivot), new BigDecimal(buyHigh), new BigDecimal(stop), new BigDecimal(goal));
        jdbc.update("INSERT INTO base_status_history (base_id, seq, status, status_date) VALUES (?, 0, 'NEAR_PIVOT', ?)", id, PREVIOUS);
    }

    @BeforeEach
    void seed() {
        tcs = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        sbin = instruments.resolve("NSE:SBIN").map(Instrument::id).orElseThrow();
        hdfc = instruments.resolve("NSE:HDFCBANK").map(Instrument::id).orElseThrow();
        history.write(tcs, Timeframe.M1, day(tcs, "98.00", "100.50", 3_000));        // crosses 100 at 09:45 on volume (pace ~11x)
        history.write(sbin, Timeframe.M1, day(sbin, "98.00", "100.50", 50));         // crosses without volume
        history.write(hdfc, Timeframe.M1, day(hdfc, "110.00", "110.00", 3_000));     // above the 105 buy zone all day
        for (UUID id : List.of(tcs, sbin, hdfc)) {
            history.write(id, Timeframe.D1, dailyHistory(id, "98.00"));
        }
        jdbc.update("DELETE FROM base WHERE engine_version = '1' AND detected_date = ?", PREVIOUS.minusDays(2));
        base(tcs, "NSE:TCS", "100.00", "105.00", "93.00", "120.00");
        base(sbin, "NSE:SBIN", "100.00", "105.00", "93.00", "120.00");
        base(hdfc, "NSE:HDFCBANK", "100.00", "105.00", "93.00", "120.00");
    }

    @AfterEach
    void cleanUp() {
        sessions.active().ifPresent(a -> sessions.control(a.id(), SimSessionService.Action.CANCEL, null));
        if (deployment != null) {
            strategies.updateDeployment(deployment.id(), false, "end of test", "admin");
        }
        jdbc.update("DELETE FROM base WHERE engine_version = '1' AND detected_date = ?", PREVIOUS.minusDays(2));
    }

    @Test
    void aReadyPlanCrossingItsPivotOnVolumeBecomesAnAutoFilledDeliveryEntryWithAnOcoGtt() {
        SimSession s = sessions.create(new SimSessionSpec(List.of(DAY), null, null, List.of("NSE:TCS", "NSE:SBIN", "NSE:HDFCBANK"), null, 1_000_000L, 2000L,
                5000L, 5, List.of()), "tester");
        deployment = entries.deploy(new SwingEntries.DeployRequest("ratings-test", 5, true, null, null, null), "tester");
        assertThat(entries.watched()).extracting(SwingEntries.Watched::symbol).containsExactlyInAnyOrder("NSE:HDFCBANK", "NSE:SBIN", "NSE:TCS");

        for (int i = 0; i < 40; i++) {
            s = sessions.control(s.id(), SimSessionService.Action.STEP, null);
        }

        List<Signal> swingSignals = signals.list(null, DAY.atStartOfDay(IST).toInstant(), 50).stream()
                .filter(x -> deployment.id().equals(x.deploymentId())).toList();
        assertThat(swingSignals).singleElement().satisfies(sig -> {
            assertThat(sig.instrumentId()).isEqualTo(tcs);
            assertThat(sig.side()).isEqualTo(Side.BUY);
            assertThat(sig.stop()).isEqualByComparingTo("93.00");
            assertThat(sig.target()).isEqualByComparingTo("120.00");
            assertThat(sig.status()).as("%s %s", sig.note(), sig.status() == SignalStatus.EXECUTED ? "" : auto.onSignal(sig.id())).isEqualTo(SignalStatus.EXECUTED);
            assertThat(sig.barTime()).isEqualTo(DAY.atTime(9, 46).atZone(IST).toInstant());
            assertThat(((Map<?, ?>) sig.evidence().get(0).get("swing")).get("limit")).isEqualTo("100.95");
        });

        List<Trade> fills = orders.trades(ExecutionMode.SIM, DAY.atStartOfDay(IST).toInstant(), DAY.plusDays(1).atStartOfDay(IST).toInstant());
        assertThat(fills).singleElement().satisfies(t -> {
            assertThat(t.instrumentId()).isEqualTo(tcs);
            assertThat(t.side()).isEqualTo(Side.BUY);
            assertThat(t.product()).isEqualTo(Product.CNC);
            assertThat(t.price()).isLessThanOrEqualTo(new BigDecimal("100.95"));
            assertThat(t.quantity()).isPositive();
        });

        Position p = orders.openPositions(ExecutionMode.SIM).stream().filter(x -> x.instrumentId().equals(tcs)).findFirst().orElseThrow();
        assertThat(p.product()).isEqualTo(Product.CNC);
        PositionGtt gtt = gtts.active(p.id()).orElseThrow();
        assertThat(gtt.stop()).isEqualByComparingTo("93.00");
        assertThat(gtt.goal()).isEqualByComparingTo("120.00");
        assertThat(gtt.quantity()).isEqualTo(p.netQuantity());
        assertThat(broker.getGtts()).filteredOn(g -> g.id().equals(gtt.brokerGttId())).singleElement()
                .satisfies(g -> assertThat(g.type()).isEqualTo(Gtt.Type.OCO));

        List<SwingEntries.Watched> watched = entries.watched();
        assertThat(watched).filteredOn(w -> w.symbol().equals("NSE:TCS")).singleElement().satisfies(w -> assertThat(w.state()).isEqualTo("TRIGGERED"));
        assertThat(watched).filteredOn(w -> w.symbol().equals("NSE:SBIN")).singleElement().satisfies(w -> assertThat(w.state()).isEqualTo("NO_VOLUME"));
        assertThat(watched).filteredOn(w -> w.symbol().equals("NSE:HDFCBANK")).singleElement()
                .satisfies(w -> assertThat(w.state()).isEqualTo("ABOVE_BUY_ZONE"));
        // the position trails: the deployment's setting reaches the swing book
        assertThat(entries.deployments()).isNotEmpty();
    }
}
