package money.hejje.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.backtest.BacktestException;
import money.hejje.backtest.BacktestService;
import money.hejje.backtest.BacktestSpec;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.event.MarketTick;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.Basket;
import money.hejje.execution.BasketService;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ExecutionException;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.internal.QuoteCache;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.risk.RiskCheck;
import money.hejje.signals.SignalService;
import money.hejje.signals.SignalStatus;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.VersionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Options end to end in PAPER (plan M5.4) on the fixture's NIFTY 2026-09-15 weekly chain, with the Sep future at 25010:
 * chain analytics, leg resolution, the options risk controls, a spread opened as a hedge-first basket and closed on its
 * combined stop, the PAPER-only lifecycle, a signal executed over REST, and the neutral iron fly (plan M6.4) opened
 * hedges first and closed when the underlying leaves its band either way.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class OptionsIT extends AbstractIntegrationTest {

    static final LocalDate EXPIRY = LocalDate.of(2026, 9, 15);

    @Autowired InstrumentService instruments;
    @Autowired OptionChainService chains;
    @Autowired OptionLegResolver resolver;
    @Autowired OptionsExecutor executor;
    @Autowired OptionsPositionMonitor monitor;
    @Autowired BasketService baskets;
    @Autowired OrderService orders;
    @Autowired StrategyService strategies;
    @Autowired SignalService signals;
    @Autowired ExecutionEngine execution;
    @Autowired FakeBrokerAdapter fake;
    @Autowired QuoteCache quoteCache;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService audit;
    @Autowired BacktestService backtests;
    @Autowired ClientCredentialService clients;
    @Autowired money.hejje.strategy.OptionsPaperEvidence paperEvidence;

    Instrument future;
    final Map<String, Instrument> opt = new HashMap<>();
    final UUID client = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE options_position, basket_leg, basket, split_order, trade, order_event, hejje_order, risk_decision, order_intent, position, "
                + "idempotency_record, strategy_position, signal, strategy_score, backtest_trade, backtest, strategy_deployment, strategy_version, strategy CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        jdbc.update("UPDATE risk_limits SET max_loss_per_day_paise = 500000, max_realized_loss_paise = 500000, max_total_loss_paise = 750000, "
                + "max_margin_utilization_pct = 80.00, max_open_positions = 5, max_trades_per_day = 20, max_risk_per_trade_paise = 200000, "
                + "max_quantity = 1000, max_notional_paise = 50000000, min_reward_risk = 1.00, mandatory_stop = TRUE, max_stop_distance_pct = 5.00, "
                + "no_new_trades_after = '14:45', no_averaging_down = TRUE, no_reentry_minutes = 10, max_consecutive_losses = 3 WHERE mode = 'PAPER'");
        instruments.sync();
        fake.reset();
        quoteCache.clear();
        clock.setIst("2026-09-10T10:00:00");
        future = instruments.futures("NIFTY").stream().filter(i -> LocalDate.of(2026, 9, 29).equals(i.expiry())).findFirst().orElseThrow();
        opt.clear();
        for (Instrument i : instruments.optionChain("NIFTY", EXPIRY)) {
            opt.put(i.strike().stripTrailingZeros().toPlainString() + i.optionType(), i);
        }
        quotes();
    }

    @AfterEach
    void tearDown() {
        clock.set(Instant.now());
    }

    void quote(Instrument i, String price, long oi) {
        BigDecimal p = new BigDecimal(price);
        MarketTick t = new MarketTick(i.id(), clock.instant(), p, p.subtract(new BigDecimal("0.05")), p.add(new BigDecimal("0.05")), 1000, oi, MarketTick.Mode.FULL); // full-mode ticks carry OI, as the chain subscribes them
        quoteCache.accept(t);
        fake.injectTick(t);
    }

    void quotes() {
        quote(future, "25010.00", 0);
        quote(opt.get("24900CE"), "180.50", 50_000);
        quote(opt.get("24900PE"), "95.20", 70_000);
        quote(opt.get("25000CE"), "120.30", 80_000);
        quote(opt.get("25000PE"), "135.80", 90_000);
        quote(opt.get("25100CE"), "75.10", 60_000);
        quote(opt.get("25100PE"), "190.00", 40_000);
    }

    static String yaml(String file) throws Exception {
        return Files.readString(Path.of("../strategies/" + file));
    }

    static <T> T await(Supplier<Optional<T>> probe, String what) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Optional<T> v = probe.get();
            if (v.isPresent()) {
                return v.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    OrderIntentCommand option(String key, String strikeType, Side side, int qty, UUID strategyId) {
        return new OrderIntentCommand(client, key, ActorType.USER, "tester", strategyId, null, opt.get(strikeType).id(), side, Quantity.of(qty), OrderType.MARKET,
                Product.MIS, null, null, null, null, null, OrderReason.MANUAL);
    }

    List<String> failures(Runnable submit) {
        try {
            submit.run();
        } catch (ExecutionException.RiskRejected e) {
            return e.checks().stream().filter(c -> !c.passed()).map(RiskCheck::name).toList();
        }
        throw new AssertionError("expected a risk rejection");
    }

    @Test
    void theChainHasTheForwardIvGreeksPcrAndMaxPain() {
        OptionChain c = chains.chain("NIFTY", EXPIRY);
        assertThat(c.forward()).isEqualByComparingTo("25010.00");
        assertThat(c.forwardSource()).isEqualTo(future.hejjeSymbol().format());
        assertThat(c.atmStrike()).isEqualByComparingTo("25000");
        assertThat(c.rows()).hasSize(3);
        // independent values (Python, math.erf) for T = 5 days 5.5 hours to 15:30 on the expiry
        OptionChain.OptionQuote atmCall = c.row(new BigDecimal("25000.00")).orElseThrow().call();
        assertThat(atmCall.iv()).isCloseTo(0.0966, within(5e-4));
        assertThat(atmCall.delta()).isCloseTo(0.5156, within(5e-4));
        assertThat(c.row(new BigDecimal("25100.00")).orElseThrow().put().delta()).isCloseTo(-0.5975, within(5e-4));
        assertThat(c.pcrOi()).isCloseTo(200_000.0 / 190_000.0, within(1e-9));
        assertThat(c.maxPain()).isEqualByComparingTo("25000"); // holder payouts 17.0M / 9.0M / 18.0M at 24900 / 25000 / 25100

        String token = adminAccessToken();
        ResponseEntity<Map> rest1 = rest.exchange("/api/v1/instruments/options/chain?underlying=NIFTY&expiry=2026-09-15", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(rest1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) rest1.getBody().get("atmStrike")).doubleValue()).isEqualTo(25000.0);
        assertThat((List) rest1.getBody().get("rows")).hasSize(3);
        Map expiries = rest.exchange("/api/v1/instruments/options/expiries?underlying=NIFTY", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class).getBody();
        assertThat((List<String>) expiries.get("expiries")).startsWith("2026-09-15", "2026-09-22", "2026-09-29");
        assertThat(rest.exchange("/api/v1/instruments/options/chain?underlying=NIFTY&expiry=2026-09-16", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void legsResolveToExpiryTypeAndStrikeForEachDirection() throws Exception {
        StrategyDefinition spread = strategies.parse(yaml("nifty_bull_call_spread.yaml"));
        assertThat(resolver.resolve(spread, future, Side.BUY)).extracting(l -> l.instrument().id(), OptionLegResolver.ResolvedLeg::side,
                OptionLegResolver.ResolvedLeg::quantity, OptionLegResolver.ResolvedLeg::hedgeFirst)
                .containsExactly(tuple(opt.get("25000CE").id(), Side.BUY, 75, true), tuple(opt.get("25100CE").id(), Side.SELL, 75, false));
        // a short signal makes directional legs puts, and the offset moves the short put lower
        assertThat(resolver.resolve(spread, future, Side.SELL)).extracting(l -> l.instrument().id(), OptionLegResolver.ResolvedLeg::side)
                .containsExactly(tuple(opt.get("25000PE").id(), Side.BUY), tuple(opt.get("24900PE").id(), Side.SELL));
        OptionLegResolver.ResolvedLeg call = resolver.resolve(strategies.parse(yaml("nifty_orb_call_buy.yaml")), future, Side.BUY).get(0);
        assertThat(call.instrument().id()).isEqualTo(opt.get("25000CE").id());
        assertThat(call.stopPrice()).isEqualByComparingTo("84.20"); // 120.30 × 0.70 = 84.21 on a 0.05 tick
        assertThat(call.targetPrice()).isEqualByComparingTo("192.50"); // 120.30 × 1.60 = 192.48

        // on the expiry day from the 13:00 cutoff, the nearest expiry is the next week's
        clock.setIst("2026-09-15T13:30:00");
        quotes();
        OptionLegResolver.ResolvedLeg next = resolver.resolve(strategies.parse(yaml("nifty_orb_call_buy.yaml")), future, Side.BUY).get(0);
        assertThat(next.instrument().expiry()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    void shortOptionsNeedDefinedRiskAndLotsPremiumAndTheExpiryDayAreLimited() throws Exception {
        List<String> naked = failures(() -> execution.submit(option("naked", "25100CE", Side.SELL, 75, null)));
        assertThat(naked).contains("optionsDefinedRisk").doesNotContain("mandatoryStop", "minRewardRisk"); // stop-based controls do not apply to legs

        HejjeOrder longCall = execution.submit(option("long", "25000CE", Side.BUY, 75, null));
        await(() -> orders.findById(longCall.id()).filter(o -> o.state() == OrderState.FILLED), "the long call to fill");
        HejjeOrder covered = execution.submit(option("covered", "25100CE", Side.SELL, 75, null));
        assertThat(covered.state()).isNotEqualTo(OrderState.REJECTED);

        // no cover on the put side, but a deployment of the strategy may allow undefined risk
        StrategyVersion v = strategies.create(yaml("nifty_orb_call_buy.yaml"), "test", "admin");
        strategies.changeStatus(v.strategyId(), 1, VersionStatus.PAPER, "options go straight to paper", "admin");
        strategies.deploy(v.strategyId(), 1, ExecutionMode.PAPER, List.of(), 0, Map.of("allow_undefined_risk", true), "admin");
        assertThat(failures(() -> execution.submit(option("naked-put", "24900PE", Side.SELL, 75, null)))).contains("optionsDefinedRisk");
        HejjeOrder allowed = execution.submit(option("allowed-put", "24900PE", Side.SELL, 75, v.strategyId()));
        assertThat(allowed.state()).isNotEqualTo(OrderState.REJECTED);

        assertThat(failures(() -> execution.submit(option("too-many", "25000CE", Side.BUY, 825, null)))).contains("optionsLots", "optionsPremium");

        clock.setIst("2026-09-15T13:30:00");
        quotes();
        assertThat(failures(() -> execution.submit(option("expiry-day", "24900CE", Side.BUY, 75, null)))).contains("optionsExpiryDay");
    }

    @Test
    void aSpreadOpensAsAHedgeFirstBasketAndClosesOnTheCombinedStopShortLegFirst() throws Exception {
        StrategyVersion v = strategies.create(yaml("nifty_bull_call_spread.yaml"), "test", "admin");
        strategies.changeStatus(v.strategyId(), 1, VersionStatus.PAPER, "options go straight to paper", "admin");
        StrategyDeployment d = strategies.deploy(v.strategyId(), 1, ExecutionMode.PAPER, List.of(), 0, Map.of(), "admin");
        OptionsExecutor.OpenRequest request = new OptionsExecutor.OpenRequest(client, "spread-1", ActorType.USER, "tester", v.strategyId(), v.id(), d.id(), null,
                future.id(), Side.BUY, new BigDecimal("24950.00"), null, v.definition());
        OptionsPosition p = executor.open(request);
        assertThat(p.status()).isEqualTo(OptionsPosition.Status.PENDING);
        assertThat(executor.open(request).id()).as("idempotent by key").isEqualTo(p.id());

        Basket basket = null;
        for (int i = 0; i < 1200 && basket == null; i++) {
            basket = baskets.find(p.basketId()).filter(b -> b.status() != Basket.Status.PENDING && b.status() != Basket.Status.EXECUTING).orElse(null);
            if (basket == null) {
                Thread.sleep(50);
            }
        }
        if (basket == null) {
            Basket stuck = baskets.find(p.basketId()).orElseThrow();
            throw new AssertionError("opening basket still " + stuck.status() + ": " + stuck.legs() + " orders "
                    + stuck.legs().stream().filter(l -> l.orderId() != null).map(l -> orders.findById(l.orderId()).map(o -> o.state() + " " + o.brokerOrderId())).toList()
                    + " fake quote " + fake.lastPrice(opt.get("25000CE").id()));
        }
        if (basket.status() != Basket.Status.COMPLETED) {
            StringBuilder why = new StringBuilder(basket.status() + ": " + basket.detail());
            for (money.hejje.execution.BasketLeg l : basket.legs()) {
                why.append(" | leg ").append(l.sequence()).append(' ').append(l.status()).append(' ').append(l.detail());
                if (l.orderId() != null) {
                    orders.findById(l.orderId()).ifPresent(o -> why.append(" order ").append(o.state()).append(" broker=").append(o.brokerOrderId())
                            .append(" last=").append(o.lastBrokerStatus()).append(" events=")
                            .append(orders.events(o.id()).stream().map(e -> e.fromState() + ">" + e.toState() + "(" + e.source() + ")").toList()));
                }
            }
            why.append(" | fake orders ").append(fake.getOrders().stream().map(o -> o.brokerOrderId() + ":" + o.status() + ":" + o.filledQuantity()).toList());
            why.append(" | fake quote 25100CE ").append(fake.lastPrice(opt.get("25100CE").id()));
            throw new AssertionError(why.toString());
        }
        assertThat(basket.legs().get(0).executionOrder()).as("the long call is the hedge, placed first").isZero();
        monitor.tick();
        OptionsPosition open = executor.find(p.id()).orElseThrow();
        assertThat(open.status()).isEqualTo(OptionsPosition.Status.OPEN);
        assertThat(open.legs()).allMatch(l -> l.entryPrice() != null && l.orderId() != null);

        // against the spread but inside the ₹2,500 combined stop: stays open
        quote(opt.get("25000CE"), "85.00", 80_000);
        quote(opt.get("25100CE"), "60.00", 60_000);
        monitor.tick();
        assertThat(executor.find(p.id()).orElseThrow().status()).isEqualTo(OptionsPosition.Status.OPEN);

        quote(opt.get("25000CE"), "70.00", 80_000);
        monitor.tick();
        OptionsPosition first = executor.find(p.id()).orElseThrow();
        assertThat(first.status()).isIn(OptionsPosition.Status.CLOSING, OptionsPosition.Status.CLOSED);
        assertThat(first.closeReason()).isEqualTo("COMBINED_STOP");
        OptionsPosition.Leg shortAtFirst = first.legs().get(1);
        OptionsPosition.Leg longAtFirst = first.legs().get(0);
        assertThat(shortAtFirst.exitOrderId()).as("the short leg is bought back first").isNotNull();
        boolean shortFilled = orders.findById(shortAtFirst.exitOrderId()).map(o -> o.state() == OrderState.FILLED).orElse(false);
        if (!shortFilled) {
            assertThat(longAtFirst.exitOrderId()).as("the long leg waits until the short is flat").isNull();
        }
        OptionsPosition closed = await(() -> {
            monitor.tick();
            return executor.find(p.id()).filter(x -> x.status() == OptionsPosition.Status.CLOSED);
        }, "the spread to close");
        OptionsPosition.Leg longLeg = closed.legs().get(0);
        OptionsPosition.Leg shortLeg = closed.legs().get(1);
        long shortFilledAt = orders.events(shortLeg.exitOrderId()).stream().filter(e -> e.toState() == OrderState.FILLED).map(e -> Ids.timestampOf(e.id()))
                .findFirst().orElseThrow();
        assertThat(Ids.timestampOf(longLeg.exitOrderId())).as("the long exit was created after the short exit filled").isGreaterThanOrEqualTo(shortFilledAt);
        BigDecimal expected = closed.legs().stream().map(l -> l.pnlAt(l.exitPrice())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(closed.realized().toRupees()).isEqualByComparingTo(expected);
        assertThat(closed.realized().paise()).isNegative();
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.OPTIONS_POSITION_CLOSED, null, 0, 50)).content())
                .anyMatch(a -> p.id().toString().equals(a.payload().get("optionsPositionId")) && "COMBINED_STOP".equals(a.payload().get("reason")));
        assertThat(paperEvidence.closedPaperPositions(v.id())).isEqualTo(1);

        // max_trades_per_day counts options positions
        assertThatThrownBy(() -> executor.open(new OptionsExecutor.OpenRequest(client, "spread-2", ActorType.USER, "tester", v.strategyId(), v.id(), d.id(), null,
                future.id(), Side.BUY, new BigDecimal("24950.00"), null, v.definition()))).isInstanceOf(IllegalStateException.class).hasMessageContaining("max_trades_per_day");
    }

    @Test
    void optionsStrategiesArePaperOnly() throws Exception {
        StrategyVersion v = strategies.create(yaml("nifty_orb_call_buy.yaml"), "test", "admin");
        assertThatThrownBy(() -> strategies.changeStatus(v.strategyId(), 1, VersionStatus.BACKTESTED, null, "admin"))
                .isInstanceOf(StrategyException.Conflict.class).hasMessageContaining("cannot be backtested");
        assertThatThrownBy(() -> backtests.submit(new BacktestSpec(v.id(), List.of(), null, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 7), null, 5, "v1", null,
                null, null), "admin")).isInstanceOf(BacktestException.class).hasMessageContaining("PAPER-only");
        strategies.changeStatus(v.strategyId(), 1, VersionStatus.PAPER, "straight to paper", "admin");
        assertThatThrownBy(() -> strategies.changeStatus(v.strategyId(), 1, VersionStatus.LIVE, null, "admin"))
                .isInstanceOf(StrategyException.Conflict.class).hasMessageContaining("needs 30 closed paper options positions");
    }

    @Test
    void anOptionsSignalExecutesOverRestAsAnOptionsPosition() throws Exception {
        StrategyVersion v = strategies.create(yaml("nifty_orb_call_buy.yaml"), "test", "admin");
        strategies.changeStatus(v.strategyId(), 1, VersionStatus.PAPER, "straight to paper", "admin");
        StrategyDeployment d = strategies.deploy(v.strategyId(), 1, ExecutionMode.PAPER, List.of(), 0, Map.of(), "admin");
        UUID signalId = UUID.randomUUID();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO signal (id, version_id, strategy_id, deployment_id, instrument_id, mode, side, reference_price, stop, target, risk_per_unit, bar_time,
                    valid_until, evidence, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PAPER', 'BUY', 25010, 24950, NULL, 60, ?, ?, '[]'::jsonb, 'ACTIVE', ?, ?)
                """, signalId, v.id(), v.strategyId(), d.id(), future.id(), now, now.plusMinutes(10), now, now);
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String key = clients.create("options-exec-" + UUID.randomUUID(), Set.of(ScopeCatalog.MARKET_READ, ScopeCatalog.ORDERS_EXECUTE), null, actor).key();
        HttpHeaders headers = bearer(key);
        headers.set("Idempotency-Key", "options-signal-1");
        ResponseEntity<Map> executed = rest.exchange("/api/v1/signals/" + signalId + "/execute", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertThat(executed.getStatusCode()).as("%s", executed.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(executed.getBody()).containsEntry("status", "PENDING").containsEntry("underlying", "NIFTY");
        UUID basketId = UUID.fromString((String) executed.getBody().get("basketId"));
        Basket basket = await(() -> baskets.find(basketId).filter(b -> b.status() == Basket.Status.COMPLETED), "the call to fill");
        assertThat(basket.legs()).singleElement().satisfies(l -> assertThat(l.instrumentId()).isEqualTo(opt.get("25000CE").id()));
        assertThat(signals.find(signalId).orElseThrow().status()).isEqualTo(SignalStatus.EXECUTED);
        assertThat(signals.find(signalId).orElseThrow().note()).startsWith("options position ");
        assertThat(rest.exchange("/api/v1/options/positions", HttpMethod.GET, new HttpEntity<>(bearer(key)), List.class).getBody()).hasSize(1);
    }

    /** Waits for the opening basket, ticks the monitor and returns the OPEN position. */
    OptionsPosition awaitOpen(OptionsPosition p) throws InterruptedException {
        Basket basket = await(() -> baskets.find(p.basketId()).filter(b -> b.status() != Basket.Status.PENDING && b.status() != Basket.Status.EXECUTING),
                "the iron fly basket");
        assertThat(basket.status()).as("%s", basket.detail()).isEqualTo(Basket.Status.COMPLETED);
        // both wings (hedge_first) are placed before either short leg
        int lastHedge = basket.legs().stream().filter(money.hejje.execution.BasketLeg::hedgeFirst).mapToInt(money.hejje.execution.BasketLeg::executionOrder).max().orElseThrow();
        int firstShort = basket.legs().stream().filter(l -> !l.hedgeFirst()).mapToInt(money.hejje.execution.BasketLeg::executionOrder).min().orElseThrow();
        assertThat(lastHedge).isLessThan(firstShort);
        monitor.tick();
        OptionsPosition open = executor.find(p.id()).orElseThrow();
        assertThat(open.status()).isEqualTo(OptionsPosition.Status.OPEN);
        return open;
    }

    @Test
    void aNeutralIronFlyOpensHedgesFirstAndClosesWhenTheUnderlyingLeavesTheBandEitherWay() throws Exception {
        StrategyDefinition fly = strategies.parse(yaml("nifty_920_iron_fly.yaml"));
        // wings at ±200 resolve to the nearest listed strikes of the fixture chain (24900 / 25100)
        assertThat(resolver.resolve(fly, future, null)).extracting(l -> l.instrument().id(), OptionLegResolver.ResolvedLeg::side, OptionLegResolver.ResolvedLeg::hedgeFirst)
                .containsExactly(tuple(opt.get("25100CE").id(), Side.BUY, true), tuple(opt.get("24900PE").id(), Side.BUY, true),
                        tuple(opt.get("25000CE").id(), Side.SELL, false), tuple(opt.get("25000PE").id(), Side.SELL, false));
        assertThatThrownBy(() -> resolver.resolve(strategies.parse(yaml("nifty_bull_call_spread.yaml")), future, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("neutral");

        StrategyVersion v = strategies.create(yaml("nifty_920_iron_fly.yaml"), "test", "admin");
        strategies.changeStatus(v.strategyId(), 1, VersionStatus.PAPER, "options go straight to paper", "admin");
        StrategyDeployment d = strategies.deploy(v.strategyId(), 1, ExecutionMode.PAPER, List.of(), 0, Map.of(), "admin");
        // the runner stores a neutral signal as BUY with its stop 150 points under the reference; execution turns it into a band
        UUID signalId = UUID.randomUUID();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO signal (id, version_id, strategy_id, deployment_id, instrument_id, mode, side, reference_price, stop, target, risk_per_unit, bar_time,
                    valid_until, evidence, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PAPER', 'BUY', 25010, 24860, NULL, 150, ?, ?, '[]'::jsonb, 'ACTIVE', ?, ?)
                """, signalId, v.id(), v.strategyId(), d.id(), future.id(), now, now.plusMinutes(10), now, now);
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        OptionsPosition p = signals.executeOptions(signalId, "fly-1", actor);
        assertThat(p.direction()).isNull();
        assertThat(p.underlyingStop()).isEqualByComparingTo("24860.00");
        assertThat(p.underlyingStopHigh()).isEqualByComparingTo("25160.00");
        assertThat(p.legs()).hasSize(4);
        OptionsPosition open = awaitOpen(p);
        assertThat(open.neutral()).isTrue();

        // inside the band: stays open; at the upper edge: closes on the band
        quote(future, "25150.00", 0);
        monitor.tick();
        assertThat(executor.find(p.id()).orElseThrow().status()).isEqualTo(OptionsPosition.Status.OPEN);
        quote(future, "25160.00", 0);
        monitor.tick();
        assertThat(executor.find(p.id()).orElseThrow().closeReason()).isEqualTo("UNDERLYING_BAND");
        OptionsPosition closedUp = await(() -> {
            monitor.tick();
            return executor.find(p.id()).filter(x -> x.status() == OptionsPosition.Status.CLOSED);
        }, "the iron fly to close on the upper edge");
        assertThat(closedUp.legs()).allMatch(l -> l.exitPrice() != null);
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.OPTIONS_POSITION_CLOSED, null, 0, 50)).content())
                .anyMatch(a -> p.id().toString().equals(a.payload().get("optionsPositionId")) && "NEUTRAL".equals(a.payload().get("direction")));

        // a second fly (no deployment, so max_trades_per_day does not apply) closes at the lower edge, after the re-entry cooldown
        clock.setIst("2026-09-10T10:30:00");
        quotes();
        OptionsPosition second = executor.open(new OptionsExecutor.OpenRequest(client, "fly-2", ActorType.USER, "tester", v.strategyId(), v.id(), null, null,
                future.id(), null, new BigDecimal("24860.00"), new BigDecimal("25160.00"), v.definition()));
        awaitOpen(second);
        quote(future, "24870.00", 0);
        monitor.tick();
        assertThat(executor.find(second.id()).orElseThrow().status()).isEqualTo(OptionsPosition.Status.OPEN);
        quote(future, "24860.00", 0);
        monitor.tick();
        assertThat(executor.find(second.id()).orElseThrow().closeReason()).isEqualTo("UNDERLYING_BAND");
        await(() -> {
            monitor.tick();
            return executor.find(second.id()).filter(x -> x.status() == OptionsPosition.Status.CLOSED);
        }, "the iron fly to close on the lower edge");
    }
}
