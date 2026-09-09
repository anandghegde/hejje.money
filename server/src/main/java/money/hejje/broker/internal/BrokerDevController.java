package money.hejje.broker.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import money.hejje.broker.fake.FakeBrokerAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Development hooks for the fake broker (docs/data.md, "Seeding"): push a quote so simulated MARKET orders fill and
 * resting SL/SL-M orders trigger. 404 unless the fake adapter is active.
 */
@RestController
@RequestMapping("/api/v1/broker/dev")
class BrokerDevController {

    private final ObjectProvider<FakeBrokerAdapter> fake;

    BrokerDevController(ObjectProvider<FakeBrokerAdapter> fake) {
        this.fake = fake;
    }

    record QuoteRequest(UUID instrumentId, BigDecimal price) {}

    @PostMapping("/quote")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, Object> quote(@RequestBody QuoteRequest request) {
        FakeBrokerAdapter adapter = fake.getIfAvailable();
        if (adapter == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "the fake broker is not active");
        }
        if (request.instrumentId() == null || request.price() == null || request.price().signum() <= 0) {
            throw new IllegalArgumentException("instrumentId and a positive price are required");
        }
        adapter.injectQuote(request.instrumentId(), request.price());
        adapter.flush();
        return Map.of("instrumentId", request.instrumentId(), "price", request.price());
    }
}
