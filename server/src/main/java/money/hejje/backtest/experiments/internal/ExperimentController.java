package money.hejje.backtest.experiments.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import money.hejje.backtest.Splits;
import money.hejje.backtest.experiments.Experiment;
import money.hejje.backtest.experiments.ExperimentService;
import money.hejje.backtest.experiments.VariantPreview;
import money.hejje.backtest.experiments.VariantSpec;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.strategy.StrategyVersion;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Strategy experiments (plan M4.7). */
@RestController
@RequestMapping("/api/v1/experiments")
class ExperimentController {

    record StartRequest(UUID versionId, String goal, List<VariantSpec> variants, LocalDate from, LocalDate to, List<UUID> instrumentIds, Splits splits) {}

    record PreviewRequest(UUID versionId, Map<String, Object> delta) {}

    private final ExperimentService experiments;

    ExperimentController(ExperimentService experiments) {
        this.experiments = experiments;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    ResponseEntity<Experiment> start(@RequestBody StartRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(experiments.start(body.versionId(), body.goal(), body.variants(),
                    new ExperimentService.DatasetRequest(body.from(), body.to(), body.instrumentIds(), body.splits()), principal.name(), null));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    VariantPreview preview(@RequestBody PreviewRequest body) {
        try {
            return experiments.preview(body.versionId(), body.delta());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<Experiment> list(@RequestParam(required = false) UUID versionId, @RequestParam(defaultValue = "20") int limit) {
        return experiments.list(versionId, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    Experiment get(@PathVariable UUID id) {
        return experiments.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment " + id + " not found"));
    }

    @PostMapping("/{id}/variants/{variantId}/promote")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    ResponseEntity<StrategyVersion> promote(@PathVariable UUID id, @PathVariable UUID variantId, @AuthenticationPrincipal HejjePrincipal principal) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(experiments.promote(id, variantId, principal.name()));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
