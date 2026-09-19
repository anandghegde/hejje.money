package money.hejje.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.market.MarketService;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.orders.Trade;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Historical session replay with simulated fills (plan M7.2) on a hand-built M1 session of NSE:INFY: 375 steps, a
 * scripted MARKET entry filling on the next tick with the paper slippage, a stop filling when crossed, no candle or
 * quote past the simulation clock, the same result at 60× and MAX, and a strategy's force exit at simulated 15:10.
 */
class SimReplayIT extends AbstractSimIT {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 9, 9);
    static final HejjePrincipal ADMIN = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);

    @Autowired SimSessionService sessions;
    @Autowired HistoricalCandleStore history;
    @Autowired InstrumentService instruments;
    @Autowired MarketService market;
    @Autowired ExecutionEngine execution;
    @Autowired OrderService orders;
    @Autowired SignalService signals;
    @Autowired StrategyService strategies;
    @Autowired HejjeClock clock;

    UUID infy;

    /**
     * Minute i opens at 1500 + (i mod 10) × 0.5 before 09:35 and 1492 + (i mod 10) × 0.5 after, closes 0.25 higher, with
     * ±0.5 wicks; the 09:35 bar drops from 1502 through 1490. Up bars replay open, low, high, close; the 09:35 bar
     * (down) open, high, low, close. 4,000 shares a minute.
     */
    static List<Candle> fixtureDay(UUID id) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < SimSession.STEPS_PER_DAY; i++) {
            Instant t = DAY.atTime(LocalTime.of(9, 15).plusMinutes(i)).atZone(IST).toInstant();
            BigDecimal open;
            BigDecimal high;
            BigDecimal low;
            BigDecimal close;
            if (i == 20) {
                open = new BigDecimal("1502.00");
                high = new BigDecimal("1502.50");
                low = new BigDecimal("1490.00");
                close = new BigDecimal("1492.00");
            } else {
                open = BigDecimal.valueOf(i < 20 ? 1500 : 1492).add(BigDecimal.valueOf(i % 10).multiply(new BigDecimal("0.5")));
                close = open.add(new BigDecimal("0.25"));
                high = close.add(new BigDecimal("0.5"));
                low = open.subtract(new BigDecimal("0.5"));
            }
            out.add(new Candle(id, Timeframe.M1, t, open.setScale(2), high.setScale(2), low.setScale(2), close.setScale(2), 4000, 0, false));
        }
        return out;
    }

    @BeforeEach
    void seed() {
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        history.write(infy, Timeframe.M1, fixtureDay(infy));
    }

    @org.junit.jupiter.api.AfterEach
    void cancelActive() {
        sessions.active().ifPresent(a -> sessions.control(a.id(), SimSessionService.Action.CANCEL, null));
    }

    @Autowired money.hejje.signals.SignalEngine engine;

    SimSession create() {
        return sessions.create(new SimSessionSpec(List.of(DAY), null, null, List.of("NSE:INFY"), null, 1_000_000L, 2000L, 5000L, 5, List.of()), "tester");
    }

    SimSession steps(SimSession s, int n) {
        SimSession out = s;
        for (int i = 0; i < n; i++) {
            out = sessions.control(s.id(), SimSessionService.Action.STEP, null);
        }
        return out;
    }

    HejjeOrder order(String key, Side side, OrderType type, String trigger, String stop) {
        try {
            return submit(key, side, type, trigger, stop);
        } catch (money.hejje.execution.ExecutionException.RiskRejected e) {
            throw new AssertionError(key + " refused: " + e.checks().stream().filter(c -> !c.passed()).map(c -> c.name() + " " + c.observed() + " vs " + c.limit() + " " + c.message()).toList(), e);
        }
    }

    HejjeOrder submit(String key, Side side, OrderType type, String trigger, String stop) {
        return execution.submit(new OrderIntentCommand(ADMIN.id(), key, ActorType.USER, "tester", null, null, infy, side, Quantity.of(10), type, Product.MIS,
                null, trigger == null ? null : Price.of(new BigDecimal(trigger)), stop == null ? null : Price.of(new BigDecimal(stop)), null,
                Money.ofRupees(100), OrderReason.MANUAL));
    }

    /** The scripted entry: buy 10 at market with a 1495 stop. */
    HejjeOrder scriptedEntry(String run) {
        return order(run + "-buy", Side.BUY, OrderType.MARKET, null, "1495.00");
    }

    /** The scripted protective stop once the entry has filled: an SL-M sell at 1495. */
    HejjeOrder scriptedStop(String run) {
        return order(run + "-stop", Side.SELL, OrderType.SL_M, "1495.00", null);
    }

    SimSession runToEnd(SimSession s, String speed) {
        sessions.control(s.id(), SimSessionService.Action.PLAY, speed);
        for (int i = 0; i < 1200; i++) {
            SimSession now = sessions.find(s.id()).orElseThrow();
            if (now.state().finished()) {
                return now;
            }
            sleep(100);
        }
        throw new AssertionError("session did not finish: " + sessions.find(s.id()).orElseThrow());
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    List<Trade> fills() {
        return orders.trades(ExecutionMode.SIM, DAY.atStartOfDay(IST).toInstant(), DAY.plusDays(1).atStartOfDay(IST).toInstant()).stream()
                .sorted(java.util.Comparator.comparing(Trade::ts)).toList();
    }

    @Test
    void aScriptedTradeFillsOnReplayedTicksAndNothingPastTheClockIsVisible() {
        SimSession s = create();
        assertThat(s.state()).isEqualTo(SimSession.State.PAUSED);
        assertThat(clock.nowIst().toLocalTime()).isEqualTo(LocalTime.of(9, 15));

        s = steps(s, 15); // 09:15 .. 09:29 replayed; the clock is at 09:30:00
        assertThat(s.progress()).isEqualTo("15/375");
        assertThat(clock.nowIst().toLocalTime()).isEqualTo(LocalTime.of(9, 30));
        // look-ahead guard: the stored day has 375 M1 candles, only the 15 closed ones are visible
        Instant dayStart = DAY.atStartOfDay(IST).toInstant();
        Instant dayEnd = DAY.plusDays(1).atStartOfDay(IST).toInstant();
        assertThat(market.candles(infy, Timeframe.M1, dayStart, dayEnd)).hasSize(15).allMatch(c -> !c.openTime().plusSeconds(60).isAfter(clock.now()));
        assertThat(market.candles(infy, Timeframe.M5, dayStart, dayEnd)).hasSize(3);
        assertThat(market.quote(infy).orElseThrow().ts()).isBeforeOrEqualTo(clock.now());
        assertThat(market.lastPrice(infy).orElseThrow()).isEqualByComparingTo("1502.25"); // the 09:29 close (1502.00 + 0.25)

        HejjeOrder buy = orders.findById(scriptedEntry("a").id()).orElseThrow();
        assertThat(buy.state()).as("a MARKET order waits for the next tick").isNotEqualTo(OrderState.FILLED);

        s = steps(s, 1); // the 09:30 open tick (1502.50) fills it, slipped 5 bps up
        Trade entry = fills().stream().filter(t -> t.side() == Side.BUY).findFirst().orElseThrow();
        assertThat(entry.price()).isEqualByComparingTo("1503.25"); // 1502.50 × 1.0005 = 1503.25125
        assertThat(entry.ts()).isEqualTo(DAY.atTime(9, 30).atZone(IST).toInstant());
        scriptedStop("a");

        s = steps(s, 4);
        assertThat(fills()).as("the stop has not been crossed before 09:35").hasSize(1);
        s = steps(s, 1); // 09:35 bar: 1502 → 1502.50 → 1490 crosses the 1495 trigger at :40
        Trade exit = fills().stream().filter(t -> t.side() == Side.SELL).findFirst().orElseThrow();
        assertThat(exit.price()).isEqualByComparingTo("1489.26"); // 1490 × 0.9995 = 1489.255
        assertThat(exit.ts()).isEqualTo(DAY.atTime(9, 35, 40).atZone(IST).toInstant());

        assertThatThrownBy(() -> create()).isInstanceOf(IllegalStateException.class).hasMessageContaining("one replay at a time");
        SimSession done = runToEnd(s, "MAX");
        assertThat(done.state()).isEqualTo(SimSession.State.DONE);
        assertThat(done.progress()).isEqualTo("375/375");
        assertThat(clock.nowIst().toLocalTime()).isAfterOrEqualTo(LocalTime.of(15, 30));
        assertThat(done.fills()).isEqualTo(2);
        assertThat(done.friction().paise()).as("costs of both fills").isPositive();
        assertThat(done.netPnl().paise()).isNegative(); // bought 1503.25, stopped 1489.26
        assertThat(done.resultHash()).hasSize(64);
        assertThat(market.candles(infy, Timeframe.M1, dayStart, dayEnd)).hasSize(375);

        // the same session with the same decisions, the entry replayed at 60× and the rest at MAX: the same result
        SimSession again = steps(create(), 15);
        scriptedEntry("b");
        again = steps(again, 1);
        scriptedStop("b");
        sessions.control(again.id(), SimSessionService.Action.PLAY, "60");
        sleep(2500);
        SimSession paused = sessions.control(again.id(), SimSessionService.Action.PAUSE, null);
        assertThat(paused.step()).isBetween(17, 21);
        SimSession doneAgain = runToEnd(paused, "MAX");
        assertThat(doneAgain.resultHash()).isEqualTo(done.resultHash());
        assertThat(doneAgain.friction()).isEqualTo(done.friction());
    }

    @Test
    void aStrategyEntersOnTheReplayAndIsForcedOutAtSimulated1510() {
        String yaml = """
                name: sim_force_exit
                universe: [NSE:INFY]
                timeframe: 5m
                direction: long
                entry:
                  all:
                    - session_minutes >= 15
                stop:
                  type: percent
                  value: 2
                trade_window:
                  start: "09:30"
                  end: "09:35"
                force_exit_time: "15:10"
                max_trades_per_day: 1
                position_sizing:
                  type: risk_based
                  risk_rupees: 2000
                """;
        StrategyVersion v = strategies.create(yaml, "sim test", "admin");
        strategies.changeStatus(v.strategyId(), 1, VersionStatus.PAPER, "sim test", "admin", true);
        var deployment = strategies.deploy(v.strategyId(), 1, ExecutionMode.SIM, List.of(), 0, Map.of(), "admin");
        try {
            SimSession s = steps(create(), 15); // the 09:25-09:30 bar closed at 09:30: session_minutes 15
            Signal signal = signals.active().stream().filter(x -> x.versionId().equals(v.id())).findFirst()
                    .orElseThrow(() -> new AssertionError("no signal; runners " + engine.runnerCount() + ", signals " + signals.list(null, null, 20)
                            + ", M5 " + market.candles(infy, Timeframe.M5, DAY.atStartOfDay(IST).toInstant(), clock.now()).size()
                            + ", runner " + engine.runner(deployment.id(), infy).map(r -> r.toString()).orElse("none")));
            assertThat(signal.side()).isEqualTo(Side.BUY);
            signals.execute(signal.id(), "sim-force-exit", ADMIN);
            SimSession done = runToEnd(s, "MAX");
            assertThat(done.state()).as("%s", done.error()).isEqualTo(SimSession.State.DONE);
            List<Trade> strategyFills = fills().stream().filter(t -> v.strategyId().equals(t.strategyId())).toList();
            assertThat(strategyFills).extracting(Trade::side).containsExactly(Side.BUY, Side.SELL);
            assertThat(strategyFills.get(0).ts()).isEqualTo(DAY.atTime(9, 30).atZone(IST).toInstant());
            // the 15:05-15:10 bar closes at simulated 15:10; the force-exit order fills on the next tick, 15:10:00
            assertThat(strategyFills.get(1).ts()).isEqualTo(DAY.atTime(15, 10).atZone(IST).toInstant());
            assertThat(strategyFills.get(1).price()).isEqualByComparingTo("1493.75"); // 1494.50 × 0.9995
        } finally {
            strategies.updateDeployment(deployment.id(), false, "end of test", "admin");
        }
    }
}
