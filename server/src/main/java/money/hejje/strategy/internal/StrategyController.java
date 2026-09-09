package money.hejje.strategy.internal;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.ValidationReport;
import money.hejje.strategy.VersionStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class StrategyController {

    private final StrategyService strategies;

    StrategyController(StrategyService strategies) {
        this.strategies = strategies;
    }

    record DefinitionRequest(@NotBlank String yaml, String changeNote) {}

    record CloneRequest(@NotBlank String name) {}

    record StatusRequest(VersionStatus status, String note, Boolean force) {}

    record DeployRequest(ExecutionMode mode, List<String> instruments, Integer autonomyLevel, Map<String, Object> params) {}

    record DeploymentUpdate(boolean enabled, String reason) {}

    @GetMapping("/strategies")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<Strategy> list() {
        return strategies.list();
    }

    @PostMapping("/strategies")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    @ResponseStatus(HttpStatus.CREATED)
    StrategyVersion create(@RequestBody DefinitionRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return strategies.create(body.yaml(), body.changeNote(), principal.name());
    }

    @PostMapping("/strategies/validate")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    ValidationReport validate(@RequestBody DefinitionRequest body) {
        return strategies.validate(body.yaml());
    }

    @GetMapping("/strategies/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    Strategy get(@PathVariable UUID id) {
        return strategies.find(id).orElseThrow(() -> new StrategyException.NotFound("Strategy " + id + " not found"));
    }

    @PostMapping("/strategies/{id}/clone")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    @ResponseStatus(HttpStatus.CREATED)
    StrategyVersion clone(@PathVariable UUID id, @RequestBody CloneRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return strategies.clone(id, body.name(), principal.name());
    }

    @GetMapping("/strategies/{id}/versions")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<StrategyVersion> versions(@PathVariable UUID id) {
        return strategies.versions(id);
    }

    @PostMapping("/strategies/{id}/versions")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    @ResponseStatus(HttpStatus.CREATED)
    StrategyVersion addVersion(@PathVariable UUID id, @RequestBody DefinitionRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return strategies.addVersion(id, body.yaml(), body.changeNote(), principal.name());
    }

    @GetMapping("/strategies/{id}/versions/{v}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    StrategyVersion version(@PathVariable UUID id, @PathVariable int v) {
        return strategies.version(id, v).orElseThrow(() -> new StrategyException.NotFound("Version " + v + " of strategy " + id + " not found"));
    }

    @PostMapping("/strategies/{id}/versions/{v}/status")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    StrategyVersion status(@PathVariable UUID id, @PathVariable int v, @RequestBody StatusRequest body,
            @AuthenticationPrincipal HejjePrincipal principal) {
        if (body.status() == null) {
            throw new IllegalArgumentException("status is required");
        }
        return strategies.changeStatus(id, v, body.status(), body.note(), principal.name(), Boolean.TRUE.equals(body.force()));
    }

    @PostMapping("/strategies/{id}/versions/{v}/deployments")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    @ResponseStatus(HttpStatus.CREATED)
    StrategyDeployment deploy(@PathVariable UUID id, @PathVariable int v, @RequestBody DeployRequest body,
            @AuthenticationPrincipal HejjePrincipal principal) {
        return strategies.deploy(id, v, body.mode() == null ? ExecutionMode.PAPER : body.mode(), body.instruments(),
                body.autonomyLevel() == null ? 0 : body.autonomyLevel(), body.params(), principal.name());
    }

    @GetMapping("/deployments")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<StrategyDeployment> deployments(@RequestParam(required = false) UUID versionId, @RequestParam(required = false) ExecutionMode mode,
            @RequestParam(required = false) Boolean enabled) {
        return strategies.deployments(versionId, mode, enabled);
    }

    @GetMapping("/deployments/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    StrategyDeployment deployment(@PathVariable UUID id) {
        return strategies.deployment(id).orElseThrow(() -> new StrategyException.NotFound("Deployment " + id + " not found"));
    }

    @PutMapping("/deployments/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    StrategyDeployment update(@PathVariable UUID id, @RequestBody DeploymentUpdate body, @AuthenticationPrincipal HejjePrincipal principal) {
        return strategies.updateDeployment(id, body.enabled(), body.reason(), principal.name());
    }
}
