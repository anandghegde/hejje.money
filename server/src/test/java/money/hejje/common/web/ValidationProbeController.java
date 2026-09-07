package money.hejje.common.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint used to exercise the problem+json handler. */
@RestController
class ValidationProbeController {

    record Probe(@NotBlank String name, @Min(1) int quantity) {}

    @PostMapping("/api/v1/test/validation")
    Probe probe(@Valid @RequestBody Probe probe) {
        if (probe.name().equals("boom")) {
            throw new IllegalArgumentException("boom is not allowed");
        }
        return probe;
    }
}
