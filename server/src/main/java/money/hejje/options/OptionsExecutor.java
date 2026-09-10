package money.hejje.options;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.OrderType;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.Basket;
import money.hejje.execution.BasketCommand;
import money.hejje.execution.BasketService;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.options.internal.OptionsStore;
import money.hejje.orders.OrderReason;
import money.hejje.strategy.StrategyDefinition;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Executes an options signal (plan M5.4): the legs are resolved on the chain and placed as an ALL_OR_NOTHING basket with
 * CLOSE_FILLED_LEGS rollback, hedge legs first, so a short leg is only placed once its long protection has filled; the
 * {@link OptionsPositionMonitor} then manages the position. Idempotent by client and key.
 */
@Service
public class OptionsExecutor {

    static final Duration BASKET_DEADLINE = Duration.ofMinutes(2);

    private final OptionLegResolver resolver;
    private final BasketService baskets;
    private final InstrumentService instruments;
    private final OptionsStore store;
    private final HejjeClock clock;
    private final HejjeProperties properties;

    OptionsExecutor(OptionLegResolver resolver, BasketService baskets, InstrumentService instruments, OptionsStore store, HejjeClock clock,
            HejjeProperties properties) {
        this.resolver = resolver;
        this.baskets = baskets;
        this.instruments = instruments;
        this.store = store;
        this.clock = clock;
        this.properties = properties;
    }

    /** @param underlyingStop the signal's stop on the underlying; crossing it closes the position */
    public record OpenRequest(UUID clientId, String idempotencyKey, ActorType source, String actorId, UUID strategyId, UUID versionId, UUID deploymentId,
            UUID signalId, UUID underlyingInstrumentId, Side direction, BigDecimal underlyingStop, StrategyDefinition definition) {}

    public OptionsPosition open(OpenRequest r) {
        Optional<OptionsPosition> existing = store.findByKey(r.clientId(), r.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        StrategyDefinition def = r.definition();
        if (def.legs().isEmpty()) {
            throw new IllegalArgumentException("Strategy " + def.name() + " has no option legs");
        }
        Instant now = clock.now();
        if (r.deploymentId() != null && store.openedSince(r.deploymentId(), clock.today().atStartOfDay(clock.zone()).toInstant()) >= def.maxTradesPerDay()) {
            throw new IllegalStateException(def.name() + " already opened " + def.maxTradesPerDay() + " options position(s) today (max_trades_per_day)");
        }
        Instrument underlying = instruments.findById(r.underlyingInstrumentId()).orElseThrow(() -> new IllegalArgumentException("Unknown underlying instrument"));
        List<OptionLegResolver.ResolvedLeg> resolved = resolver.resolve(def, underlying, r.direction());
        // per-leg stops/targets are software exits managed by the monitor, so the leg orders carry none
        List<BasketCommand.Leg> legs = resolved.stream().map(l -> new BasketCommand.Leg(l.instrument().id(), l.side(), l.quantity(), OrderType.MARKET,
                def.product(), null, null, null, null, l.hedgeFirst())).toList();
        Basket basket = baskets.submit(new BasketCommand(r.clientId(), "options:" + r.idempotencyKey(), r.source(), r.actorId(), def.name(),
                Basket.Policy.ALL_OR_NOTHING, Basket.Rollback.CLOSE_FILLED_LEGS, BASKET_DEADLINE, r.strategyId(), OrderReason.STRATEGY_SIGNAL, legs));
        List<OptionsPosition.Leg> positionLegs = resolved.stream().map(l -> new OptionsPosition.Leg(l.sequence(), l.instrument().id(),
                l.instrument().hejjeSymbol().format(), l.side(), l.quantity(), l.stopPrice(), l.targetPrice(), l.hedgeFirst(), null, null, null, null, 0)).toList();
        boolean failed = basket.status() == Basket.Status.FAILED;
        OptionsPosition position = new OptionsPosition(Ids.newId(), properties.mode(), r.clientId(), r.source(), r.actorId(), r.strategyId(), r.versionId(),
                r.deploymentId(), r.signalId(), resolver.optionUnderlying(underlying), underlying.id(), r.direction(), r.underlyingStop(), basket.id(),
                failed ? OptionsPosition.Status.FAILED : OptionsPosition.Status.PENDING, positionLegs,
                def.combinedExit() == null ? null : def.combinedExit().stopRupees(), def.combinedExit() == null ? null : def.combinedExit().targetRupees(),
                def.forceExitTime(), def.product(), failed ? "BASKET_FAILED" : null, null, failed ? basket.detail() : null, now, failed ? now : null, now);
        try {
            store.insert(position, r.idempotencyKey());
        } catch (DuplicateKeyException e) {
            return store.findByKey(r.clientId(), r.idempotencyKey()).orElseThrow();
        }
        return position;
    }

    public Optional<OptionsPosition> find(UUID id) {
        return store.find(id);
    }

    public List<OptionsPosition> list(int limit) {
        return store.list(properties.mode(), Math.max(1, Math.min(limit, 200)));
    }
}
