package money.hejje.execution.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Product;
import money.hejje.common.config.HejjeProperties;
import money.hejje.execution.ExecutionEngine;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.orders.Trade;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PositionController {

    private final ExecutionEngine engine;
    private final OrderService orders;
    private final HejjeProperties properties;

    PositionController(ExecutionEngine engine, OrderService orders, HejjeProperties properties) {
        this.engine = engine;
        this.orders = orders;
        this.properties = properties;
    }

    record CloseRequest(@NotNull UUID instrumentId, @NotNull Product product, UUID strategyId) {}

    @GetMapping("/positions")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<Position> positions(@RequestParam(required = false) ExecutionMode mode) {
        return orders.positions(mode == null ? properties.mode() : mode);
    }

    @PostMapping("/positions/close")
    @PreAuthorize("hasAuthority('SCOPE_positions:close')")
    Map<String, Object> close(@RequestBody CloseRequest body, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        OrderController.requireKey(key);
        return Map.of("orderId", engine.closePosition(body.instrumentId(), body.product(), body.strategyId()).id());
    }

    @PostMapping("/positions/close-all")
    @PreAuthorize("hasAuthority('SCOPE_positions:close')")
    Map<String, Object> closeAll(@RequestHeader(name = "Idempotency-Key", required = false) String key) {
        OrderController.requireKey(key);
        return Map.of("closed", engine.closeAllPositions());
    }

    @GetMapping("/trades")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<Trade> trades(@RequestParam(required = false) ExecutionMode mode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return orders.trades(mode == null ? properties.mode() : mode, from, to);
    }
}
