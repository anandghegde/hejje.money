package money.hejje.signals.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditService;
import money.hejje.common.ExecutionMode;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ModifyCommand;
import money.hejje.market.MarketService;
import money.hejje.market.QuoteSnapshot;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderRole;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalStatus;
import money.hejje.strategy.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tick-scripted touch moves, the re-quote limit, the chase cap, the deadline and a partial fill (plan M9.8). */
class PassiveEntryTest {

    static final UUID INSTR = UUID.randomUUID();
    static final BigDecimal TICK = new BigDecimal("0.05");

    MutableClock wall;
    ExecutionEngine execution;
    OrderService orders;
    SignalStore store;
    AuditService audit;
    PassiveEntries passive;
    HejjeOrder order;
    Signal signal;

    @BeforeEach
    void setUp() {
        wall = new MutableClock(Instant.parse("2026-09-08T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
        execution = mock(ExecutionEngine.class);
        orders = mock(OrderService.class);
        store = mock(SignalStore.class);
        audit = mock(AuditService.class);
        passive = new PassiveEntries(execution, orders, mock(MarketService.class), store, audit, mock(TickBus.class),
                new HejjeClock(wall, ZoneId.of("Asia/Kolkata"), (d, e) -> false));
        signal = new Signal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), INSTR, ExecutionMode.PAPER, Side.BUY,
                new BigDecimal("100.00"), new BigDecimal("98.00"), null, new BigDecimal("2.00"), wall.instant(), wall.instant().plusSeconds(300), List.of(),
                SignalStatus.EXECUTED, null, null, null, wall.instant(), wall.instant());
        order = order(0, OrderState.OPEN, "99.95");
        when(orders.findById(order.id())).thenAnswer(i -> Optional.of(order));
        when(store.find(signal.id())).thenReturn(Optional.of(signal));
    }

    HejjeOrder order(int filled, OrderState state, String limit) {
        UUID id = order == null ? UUID.randomUUID() : order.id();
        return new HejjeOrder(id, UUID.randomUUID(), ExecutionMode.PAPER, "fake", "B1", "t", INSTR, Side.BUY, 100, filled,
                filled > 0 ? new BigDecimal("99.95") : BigDecimal.ZERO, OrderType.LIMIT, Product.MIS, new BigDecimal(limit), null, state, null, wall.instant(),
                wall.instant(), null, OrderRole.ENTRY);
    }

    static QuoteSnapshot quote(String bid, String ask) {
        return new QuoteSnapshot(INSTR, Instant.now(), new BigDecimal(bid).add(new BigDecimal("0.05")), new BigDecimal(bid), new BigDecimal(ask), 0, 0, false);
    }

    PassiveEntries.Working working() {
        return new PassiveEntries.Working(signal.id(), order.id(), INSTR, Side.BUY, order.limitPrice(), 0, 2, wall.instant().plusSeconds(90),
                new BigDecimal("100.10"), TICK, signal.strategyId());
    }

    void start(StrategyDefinition.EntryOrder spec) {
        passive.track(signal, order, spec, TICK);
    }

    PassiveEntries.Working current() {
        return passive.workingOrders().isEmpty() ? null : currentMap();
    }

    @SuppressWarnings("unchecked")
    PassiveEntries.Working currentMap() {
        try {
            var f = PassiveEntries.class.getDeclaredField("working");
            f.setAccessible(true);
            return ((java.util.Map<UUID, PassiveEntries.Working>) f.get(passive)).get(order.id());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theTouchAtStartIsTheBidOnTheTickGrid() {
        assertThat(PassiveEntries.touch(Side.BUY, quote("99.97", "100.03"), BigDecimal.TEN, TICK)).isEqualByComparingTo("99.95");
        assertThat(PassiveEntries.touch(Side.SELL, quote("99.97", "100.03"), BigDecimal.TEN, TICK)).isEqualByComparingTo("100.05");
        assertThat(PassiveEntries.touch(Side.BUY, null, new BigDecimal("101.02"), TICK)).isEqualByComparingTo("101.00");
    }

    @Test
    void reQuotesFollowTheTouchAtMostMaxTimesThenCancel() {
        start(new StrategyDefinition.EntryOrder(StrategyDefinition.EntryOrderType.LIMIT_TOUCH, 2, 90, new BigDecimal("50")));
        passive.evaluate(current(), quote("99.95", "100.05"));             // at the touch: nothing
        verify(execution, never()).modify(any(), any());
        passive.evaluate(current(), quote("100.00", "100.10"));            // the bid moved up: re-quote 1
        passive.evaluate(current(), quote("100.10", "100.20"));            // re-quote 2
        ArgumentCaptor<ModifyCommand> m = ArgumentCaptor.forClass(ModifyCommand.class);
        verify(execution, times(2)).modify(eq(order.id()), m.capture());
        assertThat(m.getAllValues()).extracting(c -> c.limitPrice().value().toPlainString()).containsExactly("100.00", "100.10");
        assertThat(m.getAllValues()).allSatisfy(c -> assertThat(c.quantity()).isNull()); // quantity is never touched
        passive.evaluate(current(), quote("100.20", "100.30"));            // away again with the re-quotes spent: cancel
        verify(execution).cancel(order.id());
        assertThat(passive.workingOrders()).isEmpty();
        ArgumentCaptor<Signal> expired = ArgumentCaptor.forClass(Signal.class);
        verify(store).update(expired.capture());
        assertThat(expired.getValue().status()).isEqualTo(SignalStatus.EXPIRED);
        assertThat(expired.getValue().note()).startsWith("ENTRY_NOT_FILLED");
    }

    @Test
    void aReQuoteNeverChasesBeyondTheCap() {
        // 10 bps over the signal's 100.00 = 100.10
        start(new StrategyDefinition.EntryOrder(StrategyDefinition.EntryOrderType.LIMIT_TOUCH, 5, 90, BigDecimal.TEN));
        passive.evaluate(current(), quote("100.40", "100.50"));
        ArgumentCaptor<ModifyCommand> m = ArgumentCaptor.forClass(ModifyCommand.class);
        verify(execution).modify(eq(order.id()), m.capture());
        assertThat(m.getValue().limitPrice().value()).isEqualByComparingTo("100.10");
        passive.evaluate(current(), quote("100.60", "100.70")); // at the cap already: wait
        verify(execution, times(1)).modify(any(), any());
        verify(execution, never()).cancel(any());
    }

    @Test
    void theDeadlineCancelsAnUnfilledEntry() {
        start(new StrategyDefinition.EntryOrder(StrategyDefinition.EntryOrderType.LIMIT_TOUCH, 3, 30, BigDecimal.TEN));
        wall.set(wall.instant().plusSeconds(31));
        passive.evaluate(current(), null);
        verify(execution).cancel(order.id());
        verify(store).update(any(Signal.class));
    }

    @Test
    void aPartialFillKeepsItsQuantityAndCancelsTheRest() {
        start(new StrategyDefinition.EntryOrder(StrategyDefinition.EntryOrderType.LIMIT_TOUCH, 3, 90, BigDecimal.TEN));
        order = order(40, OrderState.OPEN, "99.95");
        passive.evaluate(current(), quote("100.30", "100.40"));
        verify(execution).cancel(order.id());
        verify(execution, never()).modify(any(), any());
        verify(store, never()).update(any(Signal.class)); // the signal keeps its fill: the position opens with 40
    }

    @Test
    void aFilledOrderIsForgotten() {
        start(new StrategyDefinition.EntryOrder(StrategyDefinition.EntryOrderType.LIMIT_TOUCH, 3, 90, BigDecimal.TEN));
        order = order(100, OrderState.FILLED, "99.95");
        passive.evaluate(current(), quote("100.30", "100.40"));
        assertThat(passive.workingOrders()).isEmpty();
        verify(execution, never()).cancel(any());
        List<Object> unused = new ArrayList<>();
        assertThat(unused).isEmpty();
    }
}
