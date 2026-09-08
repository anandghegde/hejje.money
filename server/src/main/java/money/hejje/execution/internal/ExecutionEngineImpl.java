package money.hejje.execution.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrderRef;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.Validity;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ExecutionException;
import money.hejje.execution.ModifyCommand;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.IntentStatus;
import money.hejje.orders.OrderEventSource;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderIntentCreatedEvent;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderRole;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.orders.OrderSubmittedEvent;
import money.hejje.orders.Position;
import money.hejje.risk.RiskDecision;
import money.hejje.risk.RiskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The execution pipeline (PRD section 36): idempotency, validation, risk, submit, and post-submit reconciliation. */
@Service
public class ExecutionEngineImpl implements ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngineImpl.class);

    private final BrokerAdapter broker;
    private final OrderService orders;
    private final OrderValidator validator;
    private final RiskEngine risk;
    private final RiskDecisionStore riskDecisions;
    private final IdempotencyStore idempotency;
    private final UnknownOrderResolver unknownResolver;
    private final AuditService audit;
    private final HejjeClock clock;
    private final HejjeProperties properties;
    private final ApplicationEventPublisher events;

    ExecutionEngineImpl(BrokerAdapter broker, OrderService orders, OrderValidator validator, RiskEngine risk,
            RiskDecisionStore riskDecisions, IdempotencyStore idempotency, UnknownOrderResolver unknownResolver, AuditService audit,
            HejjeClock clock, HejjeProperties properties, ApplicationEventPublisher events) {
        this.broker = broker;
        this.orders = orders;
        this.validator = validator;
        this.risk = risk;
        this.riskDecisions = riskDecisions;
        this.idempotency = idempotency;
        this.unknownResolver = unknownResolver;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
        this.events = events;
    }

    @Override
    public HejjeOrder submit(OrderIntentCommand command) {
        String hash = requestHash(command);
        boolean owns = idempotency.begin(command.clientId(), command.idempotencyKey(), hash);
        if (!owns) {
            return replay(command, hash);
        }
        try {
            return runPipeline(command);
        } catch (ExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            idempotency.abandon(command.clientId(), command.idempotencyKey());
            throw e;
        }
    }

    private HejjeOrder replay(OrderIntentCommand command, String hash) {
        IdempotencyStore.Record record = idempotency.find(command.clientId(), command.idempotencyKey())
                .orElseThrow(ExecutionException.IdempotencyInFlight::new);
        if (!record.requestHash().equals(hash)) {
            throw new ExecutionException.IdempotencyMismatch();
        }
        if (record.responseStatus() == null) {
            throw new ExecutionException.IdempotencyInFlight();
        }
        Map<String, Object> body = idempotency.readBody(record.responseBody());
        Object orderId = body.get("orderId");
        if (orderId != null) {
            return orders.findById(UUID.fromString(orderId.toString())).orElseThrow();
        }
        // stored a rejection response
        if (body.containsKey("checks")) {
            throw new ExecutionException.RiskRejected(List.of());
        }
        throw new ExecutionException.Validation(List.of(String.valueOf(body.getOrDefault("detail", "rejected"))));
    }

    private OrderIntent persistIntent(OrderIntentCommand command) {
        OrderIntent intent = new OrderIntent(Ids.newId(), command.idempotencyKey(), command.clientId(),
                command.source() == null ? ActorType.USER : command.source(), command.actorId(), command.strategyId(),
                command.signalId(), command.instrumentId(), command.side(), command.quantity(), command.orderType(), command.product(),
                command.limitPrice(), command.triggerPrice(), command.stopPrice(), command.targetPrice(), command.maxRisk(),
                command.reason() == null ? OrderReason.MANUAL : command.reason(), properties.mode(), IntentStatus.CREATED, List.of(),
                clock.now());
        orders.saveIntent(intent);
        events.publishEvent(new OrderIntentCreatedEvent(EventMeta.create(clock), intent.id(), intent.instrumentId()));
        audit.record(AuditEvent.of(AuditEventType.ORDER_INTENT_CREATED, intent.source()).withActorId(intent.actorId())
                .withOrderIntentId(intent.id()).withStrategyId(intent.strategyId()).withSignalId(intent.signalId())
                .withPayload(Map.of("instrumentId", intent.instrumentId().toString(), "side", intent.side().name(),
                        "quantity", intent.quantity().value(), "reason", intent.reason().name())));
        return intent;
    }

    private HejjeOrder runPipeline(OrderIntentCommand command) {
        OrderIntent intent = persistIntent(command);

        OrderIntent validating = intent.withStatus(IntentStatus.VALIDATING, List.of());
        orders.updateIntentStatus(validating);
        List<String> errors = validator.validate(validating);
        if (!errors.isEmpty()) {
            orders.updateIntentStatus(validating.withStatus(IntentStatus.FAILED, errors));
            idempotency.complete(command.clientId(), command.idempotencyKey(), 422, Map.of("detail", "Validation failed", "errors", errors));
            throw new ExecutionException.Validation(errors);
        }

        RiskDecision decision = risk.evaluate(validating);
        riskDecisions.save(validating.id(), decision, Map.of());
        if (!decision.isApproved()) {
            orders.updateIntentStatus(validating.withStatus(IntentStatus.RISK_REJECTED, decision.failures()));
            audit.record(AuditEvent.of(AuditEventType.RISK_CHECK_FAILED, intent.source()).withOrderIntentId(intent.id())
                    .withPayload(Map.of("failures", decision.failures())));
            idempotency.complete(command.clientId(), command.idempotencyKey(), 422, Map.of("detail", "Risk rejected", "checks", "see risk_decision"));
            throw new ExecutionException.RiskRejected(decision.checks());
        }
        audit.record(AuditEvent.of(AuditEventType.RISK_CHECK_PASSED, intent.source()).withOrderIntentId(intent.id()));

        HejjeOrder order = createReadyOrder(intent);
        orders.updateIntentStatus(validating.withStatus(IntentStatus.READY, List.of()));

        orders.transition(order.id(), OrderState.SUBMITTING, OrderEventSource.SYSTEM, Map.of());
        audit.record(AuditEvent.of(AuditEventType.ORDER_SUBMITTED, intent.source()).withActorId(intent.actorId())
                .withOrderIntentId(intent.id()).withOrderId(order.id()));

        HejjeOrder result = place(order, intent);
        idempotency.complete(command.clientId(), command.idempotencyKey(), 201, Map.of("orderId", order.id().toString(),
                "state", result.state().name()));
        return result;
    }

    private HejjeOrder createReadyOrder(OrderIntent intent) {
        UUID orderId = Ids.newId();
        String tag = tagFor(orderId);
        HejjeOrder order = new HejjeOrder(orderId, intent.id(), intent.mode(), broker.brokerCode(), null, tag, intent.instrumentId(),
                intent.side(), intent.quantity().value(), 0, java.math.BigDecimal.ZERO.setScale(2), intent.orderType(), intent.product(),
                intent.limitPrice() == null ? null : intent.limitPrice().value(),
                intent.triggerPrice() == null ? null : intent.triggerPrice().value(), OrderState.READY, null, null, clock.now(),
                null, roleFor(intent.reason()));
        return orders.create(order, OrderEventSource.SYSTEM);
    }

    private HejjeOrder place(HejjeOrder order, OrderIntent intent) {
        BrokerOrderRequest request = new BrokerOrderRequest(order.instrumentId(), order.side(),
                money.hejje.common.Quantity.of(order.quantity()), order.orderType(), order.product(),
                order.limitPrice() == null ? null : money.hejje.common.Price.of(order.limitPrice()),
                order.triggerPrice() == null ? null : money.hejje.common.Price.of(order.triggerPrice()), Validity.DAY, order.tag());
        try {
            BrokerOrderRef ref = broker.placeOrder(request);
            orders.attachBrokerOrderId(order.id(), ref.brokerOrderId(), order.tag());
            // async broker updates may already have advanced the order; only move to BROKER_ACCEPTED if still SUBMITTING
            orders.transitionIfCurrent(order.id(), OrderState.SUBMITTING, OrderState.BROKER_ACCEPTED, OrderEventSource.SYSTEM,
                    Map.of("brokerOrderId", ref.brokerOrderId()));
            orders.updateIntentStatus(loadIntent(intent.id()).withStatus(IntentStatus.SUBMITTED, List.of()));
            events.publishEvent(new OrderSubmittedEvent(EventMeta.create(clock), order.id(), ref.brokerOrderId()));
            return orders.findById(order.id()).orElseThrow();
        } catch (BrokerException e) {
            if (e.outcomeUnknown()) {
                HejjeOrder unknown = orders.transition(order.id(), OrderState.UNKNOWN, OrderEventSource.SYSTEM,
                        Map.of("error", e.kind().name(), "message", e.brokerMessage()));
                orders.updateIntentStatus(loadIntent(intent.id()).withStatus(IntentStatus.SUBMITTED, List.of()));
                log.warn("Order {} outcome unknown ({}); scheduling reconciliation", order.id(), e.kind());
                unknownResolver.scheduleResolve(order.id());
                return unknown;
            }
            HejjeOrder rejected = orders.transition(order.id(), OrderState.REJECTED, OrderEventSource.SYSTEM,
                    Map.of("error", e.kind().name(), "message", e.brokerMessage()));
            orders.updateIntentStatus(loadIntent(intent.id()).withStatus(IntentStatus.FAILED, List.of(e.brokerMessage())));
            return rejected;
        }
    }

    @Override
    public HejjeOrder modify(UUID orderId, ModifyCommand command) {
        HejjeOrder order = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("No order " + orderId));
        orders.transition(orderId, OrderState.MODIFY_PENDING, OrderEventSource.USER, Map.of());
        try {
            broker.modifyOrder(new BrokerOrderRef(order.brokerOrderId()), new money.hejje.broker.BrokerModifyRequest(
                    command.quantity(), command.orderType(), command.limitPrice(), command.triggerPrice(), null));
            audit.record(AuditEvent.of(AuditEventType.ORDER_MODIFIED, ActorType.USER).withOrderId(orderId));
            return orders.findById(orderId).orElseThrow();
        } catch (BrokerException e) {
            orders.transition(orderId, OrderState.OPEN, OrderEventSource.SYSTEM, Map.of("modifyError", e.brokerMessage()));
            throw e;
        }
    }

    @Override
    public HejjeOrder cancel(UUID orderId) {
        HejjeOrder order = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("No order " + orderId));
        orders.transition(orderId, OrderState.CANCEL_PENDING, OrderEventSource.USER, Map.of());
        broker.cancelOrder(new BrokerOrderRef(order.brokerOrderId()));
        return orders.findById(orderId).orElseThrow();
    }

    @Override
    public int cancelAllOpen() {
        List<HejjeOrder> open = orders.live(properties.mode()).stream()
                .filter(o -> o.state() == OrderState.OPEN || o.state() == OrderState.PARTIALLY_FILLED || o.state() == OrderState.BROKER_ACCEPTED)
                .filter(o -> o.brokerOrderId() != null).toList();
        int cancelled = 0;
        for (HejjeOrder order : open) {
            try {
                cancel(order.id());
                cancelled++;
            } catch (RuntimeException e) {
                log.warn("Cancel-all: order {} failed: {}", order.id(), e.getMessage());
            }
        }
        return cancelled;
    }

    @Override
    public HejjeOrder closePosition(UUID instrumentId, money.hejje.common.Product product, UUID strategyId) {
        Position position = orders.positions(properties.mode()).stream()
                .filter(p -> p.instrumentId().equals(instrumentId) && p.product() == product && !p.isFlat())
                .filter(p -> java.util.Objects.equals(p.strategyId(), strategyId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No open position for instrument " + instrumentId));
        money.hejje.common.Side side = position.netQuantity() > 0 ? money.hejje.common.Side.SELL : money.hejje.common.Side.BUY;
        OrderIntentCommand command = new OrderIntentCommand(systemClientId(), "close-" + Ids.newId(), ActorType.SYSTEM, "close-position",
                strategyId, null, instrumentId, side, money.hejje.common.Quantity.of(Math.abs(position.netQuantity())),
                money.hejje.common.OrderType.MARKET, product, null, null, null, null, null, OrderReason.POSITION_CLOSE);
        HejjeOrder order = submit(command);
        audit.record(AuditEvent.of(AuditEventType.POSITION_CLOSED, ActorType.SYSTEM).withOrderId(order.id())
                .withPayload(Map.of("instrumentId", instrumentId.toString(), "quantity", Math.abs(position.netQuantity()))));
        return order;
    }

    @Override
    public int closeAllPositions() {
        int closed = 0;
        for (Position position : orders.openPositions(properties.mode())) {
            try {
                closePosition(position.instrumentId(), position.product(), position.strategyId());
                closed++;
            } catch (RuntimeException e) {
                log.warn("Close-all: position {} failed: {}", position.instrumentId(), e.getMessage());
            }
        }
        return closed;
    }

    private static UUID systemClientId() {
        return new UUID(0, 1);
    }

    private OrderIntent loadIntent(UUID id) {
        return orders.findIntent(id).orElseThrow();
    }

    static OrderRole roleFor(OrderReason reason) {
        return switch (reason) {
            case POSITION_CLOSE, STRATEGY_EXIT, KILL_SWITCH -> OrderRole.EXIT;
            default -> OrderRole.ENTRY;
        };
    }

    static String tagFor(UUID orderId) {
        String hex = orderId.toString().replace("-", "");
        return hex.substring(hex.length() - 16);
    }

    static String requestHash(OrderIntentCommand c) {
        String canonical = String.join("|", str(c.instrumentId()), str(c.side()), str(c.quantity()), str(c.orderType()),
                str(c.product()), str(c.limitPrice()), str(c.triggerPrice()), str(c.stopPrice()), str(c.targetPrice()),
                str(c.reason()), str(c.strategyId()), str(c.signalId()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
