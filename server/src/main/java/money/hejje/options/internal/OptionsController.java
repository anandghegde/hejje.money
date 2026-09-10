package money.hejje.options.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import money.hejje.options.OptionChain;
import money.hejje.options.OptionChainService;
import money.hejje.options.OptionsExecutor;
import money.hejje.options.OptionsPosition;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Option chains (plan M5.4) and options positions. */
@RestController
@RequestMapping("/api/v1")
class OptionsController {

    private final OptionChainService chains;
    private final OptionsExecutor positions;

    OptionsController(OptionChainService chains, OptionsExecutor positions) {
        this.chains = chains;
        this.positions = positions;
    }

    @GetMapping("/instruments/options/expiries")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Map<String, Object> expiries(@RequestParam String underlying) {
        return Map.of("underlying", underlying.trim().toUpperCase(), "expiries", chains.expiries(underlying));
    }

    @GetMapping("/instruments/options/chain")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    OptionChain chain(@RequestParam String underlying, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry) {
        LocalDate e = expiry;
        if (e == null) {
            List<LocalDate> all = chains.expiries(underlying);
            if (all.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No upcoming option expiry for " + underlying);
            }
            e = all.get(0);
        }
        try {
            return chains.chain(underlying, e);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @GetMapping("/options/positions")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<OptionsPosition> list(@RequestParam(defaultValue = "20") int limit) {
        return positions.list(limit);
    }

    @GetMapping("/options/positions/{id}")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    OptionsPosition get(@PathVariable UUID id) {
        return positions.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No options position " + id));
    }
}
