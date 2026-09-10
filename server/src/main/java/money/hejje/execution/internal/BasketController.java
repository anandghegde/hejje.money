package money.hejje.execution.internal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.execution.Basket;
import money.hejje.execution.BasketCommand;
import money.hejje.execution.BasketService;
import money.hejje.orders.OrderReason;
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

/** Baskets (PRD 34, plan M5.3): submit with {@code orders:execute} and an Idempotency-Key, read with {@code market:read}. */
@RestController
@RequestMapping("/api/v1/baskets")
class BasketController {

    record LegRequest(UUID instrumentId, Side side, Integer quantity, OrderType orderType, Product product, Price limitPrice, Price triggerPrice, Price stopPrice,
            Price targetPrice, Boolean hedgeFirst) {}

    record BasketRequest(String name, Basket.Policy policy, Basket.Rollback rollback, Integer deadlineSeconds, UUID strategyId, List<LegRequest> legs) {}

    private final BasketService baskets;

    BasketController(BasketService baskets) {
        this.baskets = baskets;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_orders:execute')")
    ResponseEntity<Basket> submit(@RequestBody BasketRequest body, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal HejjePrincipal principal) {
        String key = OrderController.requireKey(idempotencyKey);
        List<BasketCommand.Leg> legs = body.legs() == null ? List.of() : body.legs().stream().map(l -> new BasketCommand.Leg(l.instrumentId(), l.side(),
                l.quantity() == null ? 0 : l.quantity(), l.orderType() == null ? OrderType.MARKET : l.orderType(), l.product() == null ? Product.MIS : l.product(),
                l.limitPrice(), l.triggerPrice(), l.stopPrice(), l.targetPrice(), Boolean.TRUE.equals(l.hedgeFirst()))).toList();
        ActorType actor = principal.type() == HejjePrincipal.Type.CLIENT ? ActorType.AGENT : ActorType.USER;
        Basket basket = baskets.submit(new BasketCommand(principal.id(), key, actor, principal.name(), body.name(), body.policy(), body.rollback(),
                body.deadlineSeconds() == null ? null : Duration.ofSeconds(body.deadlineSeconds()), body.strategyId(), OrderReason.MANUAL, legs));
        return ResponseEntity.status(HttpStatus.CREATED).body(basket);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<Basket> list(@RequestParam(defaultValue = "20") int limit) {
        return baskets.list(limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Basket get(@PathVariable UUID id) {
        return baskets.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No basket " + id));
    }
}
