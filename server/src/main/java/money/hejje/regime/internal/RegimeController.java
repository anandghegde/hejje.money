package money.hejje.regime.internal;

import java.time.LocalDate;
import java.util.List;
import money.hejje.regime.RegimeLabelResult;
import money.hejje.regime.RegimeService;
import money.hejje.regime.RegimeSnapshot;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/context/regime")
@PreAuthorize("hasAuthority('SCOPE_market:read')")
class RegimeController {

    private final RegimeService regime;

    RegimeController(RegimeService regime) {
        this.regime = regime;
    }

    @GetMapping
    RegimeSnapshot current() {
        return regime.current();
    }

    @GetMapping("/history")
    List<RegimeSnapshot> history(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return regime.history(from, to);
    }

    @GetMapping("/intraday")
    List<RegimeSnapshot> intraday(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return regime.intradaySnapshots(date);
    }

    @PostMapping("/label")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    RegimeLabelResult label(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return regime.labelHistory(from, to);
    }
}
