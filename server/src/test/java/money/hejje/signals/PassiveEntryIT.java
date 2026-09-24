package money.hejje.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.backtest.SyntheticSessions;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.CandleClosedEvent;
import money.hejje.market.internal.InProcessTickBus;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.signals.internal.PassiveEntries;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A {@code limit_touch} entry end to end on AUTO (plan M9.8): the entry rests at the bid, re-quotes when the touch moves
 * away, and a partial fill keeps its quantity: the rest is cancelled and the position opens with a stop for what filled.
 * Session 2026-10-16.
 */
class PassiveEntryIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    static final String YAML = """
            name: it_passive_orb
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
            max_trades_per_day: 3
            entry_order:
              type: limit_touch
              max_requotes: 2
              cancel_after_seconds: 120
            """;

    @Autowired StrategyService strategies;
    @Autowired SignalService signals;
    @Autowired SignalEngine engine;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired TickBus bus;
    @Autowired OrderService orders;
    @Autowired AuditService audit;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;
    @Autowired money.hejje.market.internal.MarketPipeline pipeline;
    @Autowired PassiveEntries passive;

    UUID infy;
    StrategyDeployment deployment;

    @BeforeEach
    void setUp() throws InterruptedException {
        engine.stop();
        jdbc.execute("TRUNCATE approval, strategy_position, signal, strategy_score, strategy_deployment, strategy_version, strategy, trade, order_event, "
                + "hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        jdbc.update("UPDATE risk_limits SET max_loss_per_day_paise = 500000, max_realized_loss_paise = 500000, max_total_loss_paise = 750000, "
                + "max_margin_utilization_pct = 80.00, max_open_positions = 5, max_trades_per_day = 20, max_risk_per_trade_paise = 200000, "
                + "max_quantity = 1000, max_notional_paise = 50000000, min_reward_risk = 1.00, mandatory_stop = TRUE, max_stop_distance_pct = 5.00, "
                + "no_new_trades_after = '14:45', no_averaging_down = TRUE, no_reentry_minutes = 10, max_consecutive_losses = 3 WHERE mode = 'PAPER'");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        pipeline.flush();
        clock.setIst(DAY + "T09:36:00");
        StrategyVersion version = strategies.create(YAML, null, "admin");
        jdbc.update("UPDATE strategy_version SET status = 'PAPER' WHERE id = ?", version.id());
        deployment = strategies.deploy(version.strategyId(), 1, ExecutionMode.PAPER, List.of("NSE:INFY"), 4, Map.of("risk_rupees", 2000), "admin");
        jdbc.update("INSERT INTO strategy_score (id, version_id, instrument_id, computed_at, base, components, adjustments, final) "
                + "VALUES (?, ?, ?, ?, 80, '[]'::jsonb, '[]'::jsonb, 85)", UUID.randomUUID(), version.id(), infy, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        engine.start();
        engine.refresh();
        awaitAsyncListeners();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        clock.set(Instant.now());
    }

    void bar(String time, String open, String high, String low, String close) {
        Candle c = SyntheticSessions.session(infy, DAY, List.of(SyntheticSessions.bar(open, high, low, close)), 50_000).get(0);
        bus.publish(new CandleClosedEvent(new Candle(infy, c.timeframe(), DAY.atTime(LocalTime.parse(time)).atZone(SyntheticSessions.IST).toInstant(), c.open(),
                c.high(), c.low(), c.close(), c.volume(), 0, false)));
        ((InProcessTickBus) bus).drain();
    }

    static <T> T await(Supplier<Optional<T>> probe, String what) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<T> v = probe.get();
            if (v.isPresent()) {
                return v.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void aPassiveEntryReQuotesAndAPartialFillOpensWithAStopForWhatFilled() throws Exception {
        fake.injectQuote(infy, "1507.50"); // bid 1507.45, ask 1507.55
        bar("09:15", "1500", "1505", "1495", "1500");
        bar("09:20", "1500", "1503", "1497", "1501");
        bar("09:25", "1501", "1504", "1498", "1502");
        bar("09:30", "1502", "1508", "1501", "1507");
        Signal signal = await(() -> signals.list(null, null, 20).stream().filter(s -> deployment.id().equals(s.deploymentId()) && s.orderId() != null)
                .findFirst(), "an executed signal");
        HejjeOrder entry = orders.findById(signal.orderId()).orElseThrow();
        assertThat(entry.orderType()).isEqualTo(OrderType.LIMIT);
        assertThat(entry.limitPrice()).isEqualByComparingTo("1507.45"); // the bid
        assertThat(passive.workingOrders()).contains(entry.id());

        // the touch moves up: one re-quote to the new bid
        fake.injectQuote(infy, "1508.00");
        passive.sweep();
        fake.flush();
        HejjeOrder requoted = await(() -> orders.findById(entry.id()).filter(o -> o.limitPrice().compareTo(new BigDecimal("1507.95")) == 0), "the re-quote");
        assertThat(requoted.quantity()).isEqualTo(entry.quantity());
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.ENTRY_REQUOTED, null, 0, 20)).content())
                .anySatisfy(a -> assertThat(a.signalId()).isEqualTo(signal.id()));

        // part of it fills: the rest is cancelled, the position opens with what filled and a stop for it
        fake.partialFill(requoted.brokerOrderId(), 40, new BigDecimal("1507.95"));
        fake.flush();
        await(() -> orders.findById(entry.id()).filter(o -> o.filledQuantity() == 40), "the partial fill");
        passive.sweep();
        fake.flush();
        StrategyPosition open = await(() -> signals.positions(false).stream().filter(p -> deployment.id().equals(p.deploymentId()))
                .filter(p -> p.status() == PositionStatus.OPEN && p.stopOrderId() != null).findFirst(), "the open position");
        assertThat(open.quantity()).isEqualTo(40);
        assertThat(orders.findById(open.stopOrderId()).orElseThrow().quantity()).isEqualTo(40);
        assertThat(orders.findById(entry.id()).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
        assertThat(passive.workingOrders()).doesNotContain(entry.id());
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.ENTRY_NOT_FILLED, null, 0, 20)).content())
                .anySatisfy(a -> assertThat(a.payload()).containsEntry("filled", 40));
    }
}
