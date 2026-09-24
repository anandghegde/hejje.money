package money.hejje.bots.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.bots.Bot;
import money.hejje.bots.BotDecision;
import money.hejje.bots.BotDecisions;
import money.hejje.bots.BotHub;
import money.hejje.bots.BotService;
import money.hejje.common.security.HejjePrincipal;
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

/** Bots (plan M7.3, docs/bots.md). */
@RestController
@RequestMapping("/api/v1/bots")
class BotController {

    private final BotService bots;
    private final BotHub hub;
    private final BotDecisions decisions;

    BotController(BotService bots, BotHub hub, BotDecisions decisions) {
        this.bots = bots;
        this.hub = hub;
        this.decisions = decisions;
    }

    record Enabled(boolean enabled) {}

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@RequestBody BotService.Registration body, @AuthenticationPrincipal HejjePrincipal principal) {
        return view(bots.register(body, principal.name()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<Map<String, Object>> list() {
        return bots.list().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    Map<String, Object> get(@PathVariable UUID id) {
        return view(find(id));
    }

    @PostMapping("/{id}/enabled")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    Map<String, Object> enable(@PathVariable UUID id, @RequestBody Enabled body) {
        find(id);
        bots.setEnabled(id, body.enabled());
        return view(find(id));
    }

    /** A bot's answer to a decision point over REST (the WebSocket reply is the same body). */
    @PostMapping("/{id}/decisions")
    @PreAuthorize("hasAuthority('SCOPE_bot:decide')")
    List<BotDecision> decide(@PathVariable UUID id, @RequestBody BotDecision.Reply reply) {
        find(id);
        return hub.answer(id, reply);
    }

    @GetMapping("/{id}/decisions")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<BotDecision> decisions(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        find(id);
        return decisions.recent(id, limit);
    }

    private Bot find(UUID id) {
        return bots.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No bot " + id));
    }

    private Map<String, Object> view(Bot b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("name", b.name());
        m.put("version", b.version());
        m.put("kind", b.kind());
        m.put("knowledgeCutoff", b.knowledgeCutoff());
        m.put("allowedModes", b.allowedModes().stream().map(Enum::name).sorted().toList());
        m.put("strategyId", b.strategyId());
        m.put("timeframe", b.timeframe());
        m.put("decisionEveryMinutes", b.decisionEveryMinutes());
        m.put("exitConfirmVotes", b.exitConfirmVotes());
        m.put("questionSet", b.questionSet());
        m.put("universe", b.universe());
        m.put("enabled", b.enabled());
        m.put("stats", hub.stats(b.id()));
        m.put("createdAt", b.createdAt());
        return m;
    }
}
