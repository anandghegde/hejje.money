package money.hejje.instruments.internal;

import java.util.List;
import java.util.UUID;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.instruments.InstrumentSyncResult;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/instruments")
class InstrumentController {

    private final InstrumentService instruments;

    InstrumentController(InstrumentService instruments) {
        this.instruments = instruments;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<Instrument> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) Exchange exchange,
            @RequestParam(required = false) InstrumentType type,
            @RequestParam(defaultValue = "20") int limit) {
        return instruments.search(q, exchange, type, limit);
    }

    @GetMapping("/resolve")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Instrument resolve(@RequestParam String symbol) {
        return instruments.resolve(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No instrument for symbol " + symbol));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Instrument get(@PathVariable UUID id) {
        return instruments.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No instrument " + id));
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    InstrumentSyncResult sync() {
        return instruments.sync();
    }
}
