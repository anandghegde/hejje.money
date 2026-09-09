package money.hejje.pulse.internal;

import java.time.LocalDate;
import java.util.List;
import money.hejje.pulse.PulseService;
import money.hejje.pulse.PulseSnapshot;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/context/pulse")
@PreAuthorize("hasAuthority('SCOPE_market:read')")
class PulseController {

    private final PulseService pulse;

    PulseController(PulseService pulse) {
        this.pulse = pulse;
    }

    @GetMapping
    PulseSnapshot current() {
        return pulse.current();
    }

    @GetMapping("/history")
    List<PulseSnapshot> history(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return pulse.history(date);
    }
}
