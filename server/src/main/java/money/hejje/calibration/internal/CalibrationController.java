package money.hejje.calibration.internal;

import java.time.LocalDate;
import java.util.List;
import money.hejje.calibration.CalibrationReport;
import money.hejje.calibration.CalibrationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Calibration reports (plan M9.2). {@code bot} is shorthand for the purpose {@code bot:<name>}. */
@RestController
@RequestMapping("/api/v1/calibration")
class CalibrationController {

    private final CalibrationService calibration;

    CalibrationController(CalibrationService calibration) {
        this.calibration = calibration;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    CalibrationReport report(@RequestParam(required = false) String purpose, @RequestParam(required = false) String version,
            @RequestParam(required = false) String bot, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        String p = bot != null && !bot.isBlank() ? CalibrationService.botPurpose(bot) : purpose;
        if (p == null || p.isBlank()) {
            throw new IllegalArgumentException("Give a purpose or a bot");
        }
        return calibration.report(p, version == null || version.isBlank() ? null : version, from, to);
    }

    @GetMapping("/purposes")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<CalibrationService.Purpose> purposes() {
        return calibration.purposes();
    }
}
