package money.hejje.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import money.hejje.backtest.BacktestEngine;
import money.hejje.backtest.BacktestInput;
import money.hejje.backtest.BacktestResult;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.BacktestTrade;
import money.hejje.backtest.FillModel;
import money.hejje.backtest.InstrumentMeta;
import money.hejje.backtest.Splits;
import money.hejje.common.ExecutionMode;
import money.hejje.common.InstrumentType;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.costs.CostModel;
import money.hejje.common.costs.CostProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.Candle;
import money.hejje.market.indicators.Bar;
import money.hejje.signals.internal.SimulatedExecutionPort;
import money.hejje.signals.internal.StrategyRunner;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import money.hejje.strategy.dsl.EvalResult;
import money.hejje.strategy.internal.DefinitionParser;
import org.junit.jupiter.api.Test;

/**
 * Parity harness (plan M2.6): the live runner (simulated fills at bar close) and the backtester with
 * {@code fillModel=BAR_CLOSE} must produce identical signal times, stop/target levels and exit reasons for every
 * bundled strategy on the same recorded sessions.
 */
class ParityTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final UUID INSTRUMENT = UUID.fromString("00000000-0000-7000-8000-00000000abcd");
    static final InstrumentMeta META = new InstrumentMeta(INSTRUMENT, "NSE:TEST", InstrumentType.EQ, 1, new BigDecimal("0.05"));
    static final List<LocalDate> WARMUP = List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5));
    static final List<LocalDate> RECORDED = List.of(LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 7));
    static final Money RISK = Money.ofRupees(2000);

    /** Deterministic "recorded" sessions: a seeded random walk with U-shaped, lognormal volume. */
    static List<Candle> recordedSessions(long seed) {
        Random rng = new Random(seed);
        List<Candle> out = new ArrayList<>();
        double price = 1500;
        List<LocalDate> days = new ArrayList<>(WARMUP);
        days.addAll(RECORDED);
        for (LocalDate day : days) {
            price *= 1 + rng.nextGaussian() * 0.006; // overnight gap
            double drift = rng.nextGaussian() * 0.0004;
            for (int i = 0; i < 75; i++) {
                double open = price;
                double close = open * (1 + drift + rng.nextGaussian() * 0.0018);
                double high = Math.max(open, close) * (1 + Math.abs(rng.nextGaussian()) * 0.0008);
                double low = Math.min(open, close) * (1 - Math.abs(rng.nextGaussian()) * 0.0008);
                long volume = (long) (Math.exp(11 + rng.nextGaussian() * 0.5) * (i < 6 || i > 66 ? 1.8 : 1.0));
                LocalDateTime openTime = day.atTime(LocalTime.of(9, 15).plusMinutes(5L * i));
                out.add(new Candle(INSTRUMENT, Timeframe.M5, openTime.atZone(IST).toInstant(), round(open), round(high), round(low), round(close), volume, 0, false));
                price = close;
            }
        }
        return out;
    }

    static BigDecimal round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    static StrategyDefinition load(Path yaml) throws Exception {
        String text = Files.readString(yaml).replaceAll("universe:\n(  - .*\n)+", "universe: [NSE:TEST]\n");
        return new DefinitionParser().parse(text);
    }

    /** Records what the runner produced. */
    static final class Recording implements StrategyRunner.Callbacks {
        final List<Signal> signals = new ArrayList<>();
        final List<StrategyPosition> opened = new ArrayList<>();
        final List<Object[]> closed = new ArrayList<>(); // {position, reason, exitPrice}

        @Override
        public Signal signalCreated(StrategyRunner runner, Side side, Bar bar, double stop, BigDecimal target, Instant validUntil, List<EvalResult> evidence) {
            BigDecimal reference = round(bar.close());
            BigDecimal stopPrice = round(stop);
            Signal s = new Signal(UUID.randomUUID(), runner.version().id(), runner.version().strategyId(), runner.deployment().id(), INSTRUMENT, ExecutionMode.PAPER,
                    side, reference, stopPrice, target, reference.subtract(stopPrice).abs(), bar.closeTime(), validUntil, StrategyRunner.evidenceOf(evidence),
                    SignalStatus.ACTIVE, null, null, null, bar.closeTime(), bar.closeTime());
            signals.add(s);
            return s;
        }

        @Override
        public void signalConsumed(Signal signal) {
        }

        @Override
        public StrategyPosition positionOpened(StrategyRunner runner, Signal signal, int qty, BigDecimal entry, BigDecimal stop, BigDecimal target, UUID entryOrderId) {
            StrategyPosition p = new StrategyPosition(UUID.randomUUID(), signal.id(), signal.deploymentId(), signal.versionId(), signal.strategyId(), INSTRUMENT,
                    ExecutionMode.PAPER, signal.side(), qty, entry, stop, stop, target, entryOrderId, null, null, PositionStatus.PENDING_ENTRY, null, null,
                    signal.barTime(), null, signal.barTime());
            opened.add(p);
            return p;
        }

        @Override
        public void positionUpdated(StrategyPosition position) {
        }

        @Override
        public void stopPlaced(StrategyPosition position, UUID stopOrderId) {
        }

        @Override
        public void stopMissing(StrategyPosition position, String detail) {
        }

        @Override
        public void exitTriggered(StrategyPosition position, CloseReason reason, UUID exitOrderId) {
        }

        @Override
        public void positionClosed(StrategyPosition position, CloseReason reason, BigDecimal exitPrice) {
            closed.add(new Object[]{position, reason, exitPrice});
        }
    }

    static CostModel costModel() {
        return new CostModel(new CostProperties(true, new BigDecimal("20"), new BigDecimal("0.0003"), new BigDecimal("0.18"), new BigDecimal("0.000001"),
                new CostProperties.Segments(new BigDecimal("0.00025"), true, new BigDecimal("0.0000297"), new BigDecimal("0.00003"), false),
                new CostProperties.Segments(new BigDecimal("0.001"), false, new BigDecimal("0.0000297"), new BigDecimal("0.00015"), true),
                new CostProperties.Segments(new BigDecimal("0.0002"), true, new BigDecimal("0.0000173"), new BigDecimal("0.00002"), false),
                new CostProperties.Segments(new BigDecimal("0.001"), true, new BigDecimal("0.0003503"), new BigDecimal("0.00003"), false)));
    }

    @Test
    void liveRunnerAndBacktesterAgreeForEveryBundledStrategyOnTwoRecordedSessions() throws Exception {
        HejjeClock clock = new HejjeClock(Clock.fixed(LocalDateTime.of(2026, 8, 8, 10, 0).atZone(IST).toInstant(), IST), IST, (d, e) -> false);
        BacktestEngine engine = new BacktestEngine(costModel(), clock);
        List<Path> files;
        try (Stream<Path> list = Files.list(Path.of("../strategies"))) {
            files = list.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).hasSize(17);
        int totalTrades = 0;
        StringBuilder report = new StringBuilder();
        for (long seed : new long[]{7, 2026}) {
            List<Candle> candles = recordedSessions(seed);
            for (Path file : files) {
                StrategyDefinition def = load(file);
                if (!def.legs().isEmpty()) {
                    continue; // options strategies trade option legs as baskets, which the backtester does not replay (M5.4)
                }
                UUID strategyId = UUID.randomUUID();
                StrategyVersion version = new StrategyVersion(UUID.randomUUID(), strategyId, 1, "", def, "h", "parity", null, "test", Instant.EPOCH, VersionStatus.PAPER);
                StrategyDeployment deployment = new StrategyDeployment(UUID.randomUUID(), version.id(), strategyId, ExecutionMode.PAPER, List.of(INSTRUMENT), 0, true,
                        Map.of(), Instant.EPOCH, null, null, null);

                // live runner: warm up on the earlier sessions, then replay the recorded ones bar by bar
                Recording recording = new Recording();
                StrategyRunner runner = new StrategyRunner(deployment, version, META, IST, new SimulatedExecutionPort(), recording, 0, RISK);
                List<Candle> warm = candles.stream().filter(c -> c.openTime().atZone(IST).toLocalDate().isBefore(RECORDED.get(0))).toList();
                runner.warmUp(warm);
                for (Candle c : candles) {
                    if (!c.openTime().atZone(IST).toLocalDate().isBefore(RECORDED.get(0))) {
                        runner.onCandleClosed(c);
                    }
                }

                // backtester with bar-close fills and no slippage on the same candles
                BacktestSpec spec = new BacktestSpec(version.id(), List.of(INSTRUMENT), Timeframe.M5, RECORDED.get(0), RECORDED.get(1), FillModel.BAR_CLOSE, 0, null,
                        Splits.NONE, Money.ofRupees(1_000_000), RISK);
                BacktestResult result = engine.run(new BacktestInput(def, spec, Map.of(INSTRUMENT, META), Map.of(INSTRUMENT, candles), RISK));

                List<BacktestTrade> trades = result.trades();
                report.append(def.name()).append(" seed ").append(seed).append(": ").append(trades.size()).append(" trades\n");
                assertThat(recording.opened).as(def.name() + " seed " + seed + " trade count").hasSameSizeAs(trades);
                assertThat(recording.closed).as(def.name() + " closed").hasSameSizeAs(trades);
                for (int i = 0; i < trades.size(); i++) {
                    BacktestTrade t = trades.get(i);
                    StrategyPosition p = recording.opened.get(i);
                    StrategyPosition closedPosition = (StrategyPosition) recording.closed.get(i)[0];
                    Signal s = recording.signals.stream().filter(x -> x.id().equals(p.signalId())).findFirst().orElseThrow();
                    String where = def.name() + " seed " + seed + " trade " + i;
                    assertThat(s.barTime()).as(where + " signal time").isEqualTo(t.entryTime());
                    assertThat(s.stop()).as(where + " stop").isEqualByComparingTo(t.stop());
                    assertThat(closedPosition.initialStop()).as(where + " initial stop").isEqualByComparingTo(t.stop());
                    assertThat(closedPosition.entryPrice()).as(where + " entry").isEqualByComparingTo(t.entryPrice());
                    if (t.target() == null) {
                        assertThat(closedPosition.target()).as(where + " target").isNull();
                    } else {
                        assertThat(closedPosition.target()).as(where + " target").isEqualByComparingTo(t.target());
                    }
                    assertThat(((CloseReason) recording.closed.get(i)[1]).name()).as(where + " exit reason").isEqualTo(t.exitReason().name());
                    assertThat(closedPosition.quantity()).as(where + " quantity").isEqualTo(t.qty());
                }
                totalTrades += trades.size();
            }
        }
        System.out.println(report);
        assertThat(totalTrades).as("the recorded sessions must exercise the strategies").isGreaterThanOrEqualTo(4);
    }
}
