package money.hejje.execution.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.config.HejjeProperties;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ModifyCommand;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderEvent;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final ExecutionEngine engine;
    private final OrderService orders;
    private final HejjeProperties properties;
    private final money.hejje.execution.ExecutionPlanner planner;
    private final money.hejje.execution.SplitService splits;
    private final money.hejje.execution.PlanningProperties planning;

    OrderController(ExecutionEngine engine, OrderService orders, HejjeProperties properties, money.hejje.execution.ExecutionPlanner planner,
            money.hejje.execution.SplitService splits, money.hejje.execution.PlanningProperties planning) {
        this.engine = engine;
        this.orders = orders;
        this.properties = properties;
        this.planner = planner;
        this.splits = splits;
        this.planning = planning;
    }

    /**
     * Either {@code side} + {@code quantity}, or {@code targetPosition} (a smart intent, M5.3: the planner computes the
     * delta from the current position). {@code split} works the order as child orders.
     */
    record IntentRequest(
            @NotNull UUID instrumentId,
            Side side,
            Quantity quantity,
            @NotNull OrderType orderType,
            @NotNull Product product,
            Price limitPrice,
            Price triggerPrice,
            Price stopPrice,
            Price targetPrice,
            Long maxRiskPaise,
            OrderReason reason,
            UUID strategyId,
            UUID signalId,
            Integer targetPosition,
            SplitRequest split) {
    }

    record SplitRequest(int maxChildQuantity, Long delayMs, java.math.BigDecimal priceTolerancePct, Boolean cancelOnMove, Long deadlineSeconds) {}

    record ModifyRequest(Quantity quantity, OrderType orderType, Price limitPrice, Price triggerPrice) {}

    record OrderView(HejjeOrder order, List<OrderEvent> events) {}

    @PostMapping("/intents")
    @PreAuthorize("hasAuthority('SCOPE_orders:execute')")
    ResponseEntity<Object> submit(@Valid @RequestBody IntentRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal HejjePrincipal principal) {
        String key = requireKey(idempotencyKey);
        Side side = body.side();
        Quantity quantity = body.quantity();
        money.hejje.execution.ExecutionPlanner.Plan plan = null;
        if (body.targetPosition() != null) {
            if (side != null || quantity != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give targetPosition, or side and quantity, not both");
            }
            plan = planner.plan(body.instrumentId(), body.product(), body.strategyId(), body.targetPosition());
            if (plan.noop()) {
                return ResponseEntity.ok(Map.of("noop", true, "plan", plan));
            }
            side = plan.side();
            quantity = Quantity.of(plan.quantity());
        } else if (side == null || quantity == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "side and quantity are required (or give targetPosition)");
        }
        OrderIntentCommand command = new OrderIntentCommand(principal.id(), key, actor(principal), principal.name(), body.strategyId(),
                body.signalId(), body.instrumentId(), side, quantity, body.orderType(), body.product(), body.limitPrice(),
                body.triggerPrice(), body.stopPrice(), body.targetPrice(), body.maxRiskPaise() == null ? null : Money.ofPaise(body.maxRiskPaise()),
                body.reason() == null ? OrderReason.MANUAL : body.reason());
        money.hejje.execution.SplitPolicy policy = splitPolicy(body.split(), quantity.value());
        if (policy != null) {
            money.hejje.execution.SplitOrder split = splits.start(command, policy);
            return ResponseEntity.status(HttpStatus.CREATED).body(plan == null ? Map.of("split", split) : Map.of("plan", plan, "split", split));
        }
        HejjeOrder order = engine.submit(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(plan == null ? order : Map.of("plan", plan, "order", order));
    }

    /** The requested split, else the automatic one above {@code hejje.execution.planning.auto-split-above}, else null. */
    private money.hejje.execution.SplitPolicy splitPolicy(SplitRequest r, int quantity) {
        if (r != null) {
            return new money.hejje.execution.SplitPolicy(r.maxChildQuantity(), r.delayMs() == null ? 0 : r.delayMs(), r.priceTolerancePct(),
                    Boolean.TRUE.equals(r.cancelOnMove()), r.deadlineSeconds() == null ? planning.splitDeadline().toSeconds() : r.deadlineSeconds());
        }
        if (planning.autoSplitAbove() > 0 && quantity > planning.autoSplitAbove()) {
            int child = planning.autoSplitChildQuantity() > 0 ? planning.autoSplitChildQuantity() : planning.autoSplitAbove();
            return new money.hejje.execution.SplitPolicy(child, planning.autoSplitDelay().toMillis(), null, false, planning.splitDeadline().toSeconds());
        }
        return null;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<HejjeOrder> list(@RequestParam(required = false) OrderState state,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) money.hejje.common.ExecutionMode mode) {
        return orders.query(mode == null ? properties.mode() : mode, state, from, to);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    OrderView get(@PathVariable UUID id) {
        HejjeOrder order = orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No order " + id));
        return new OrderView(order, orders.events(id));
    }

    @PostMapping("/{id}/modify")
    @PreAuthorize("hasAuthority('SCOPE_orders:execute')")
    HejjeOrder modify(@PathVariable UUID id, @RequestBody ModifyRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        requireKey(idempotencyKey);
        exists(id);
        return engine.modify(id, new ModifyCommand(body.quantity(), body.orderType(), body.limitPrice(), body.triggerPrice()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_orders:cancel')")
    HejjeOrder cancel(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        requireKey(idempotencyKey);
        exists(id);
        return engine.cancel(id);
    }

    @PostMapping("/cancel-all")
    @PreAuthorize("hasAuthority('SCOPE_orders:cancel')")
    Map<String, Object> cancelAll(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        requireKey(idempotencyKey);
        return Map.of("cancelled", engine.cancelAllOpen());
    }

    private void exists(UUID id) {
        orders.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No order " + id));
    }

    private static ActorType actor(HejjePrincipal principal) {
        return principal.type() == HejjePrincipal.Type.CLIENT ? ActorType.AGENT : ActorType.USER;
    }

    static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        return key;
    }
}
