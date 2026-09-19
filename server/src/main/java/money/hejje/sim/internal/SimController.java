package money.hejje.sim.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionService;
import money.hejje.sim.SimSessionSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Replay sessions of a SIM instance (plan M7.2, docs/simulation.md). Scope {@code sim:run}. */
@RestController
@RequestMapping("/api/v1/sim/sessions")
@ConditionalOnProperty(name = "hejje.mode", havingValue = "SIM")
class SimController {

    private final SimSessionService sessions;

    SimController(SimSessionService sessions) {
        this.sessions = sessions;
    }

    record ControlRequest(String action, String speed) {}

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_sim:run')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody SimSessionSpec spec, @AuthenticationPrincipal HejjePrincipal principal) {
        try {
            return view(sessions.create(spec, principal.name()));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_sim:run')")
    List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
        return sessions.list(limit).stream().map(SimController::view).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_sim:run')")
    Map<String, Object> get(@PathVariable UUID id) {
        return view(sessions.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No session " + id)));
    }

    @PostMapping("/{id}/control")
    @PreAuthorize("hasAuthority('SCOPE_sim:run')")
    Map<String, Object> control(@PathVariable UUID id, @RequestBody ControlRequest body) {
        SimSessionService.Action action = body.action() == null || body.action().isBlank() ? null
                : SimSessionService.Action.valueOf(body.action().trim().toUpperCase());
        try {
            return view(sessions.control(id, action, body.speed()));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    static Map<String, Object> view(SimSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("state", s.state());
        m.put("speed", s.speed().label());
        m.put("day", s.dayIndex() + 1);
        m.put("days", s.days());
        m.put("sessionDate", s.sessionDate());
        m.put("step", s.step());
        m.put("progress", s.progress());
        m.put("fills", s.fills());
        m.put("frictionPaid", s.friction());
        m.put("netPnl", s.netPnl());
        m.put("resultHash", s.resultHash());
        m.put("error", s.error());
        m.put("warnings", s.warnings());
        m.put("spec", s.spec());
        m.put("createdBy", s.createdBy());
        m.put("createdAt", s.createdAt());
        m.put("finishedAt", s.finishedAt());
        return m;
    }
}
