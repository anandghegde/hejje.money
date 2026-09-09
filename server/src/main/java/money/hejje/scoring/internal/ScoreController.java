package money.hejje.scoring.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.scoring.ComparisonRow;
import money.hejje.scoring.ScoreBreakdown;
import money.hejje.scoring.ScoringService;
import money.hejje.scoring.VersionComparison;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategies")
class ScoreController {

    private final ScoringService scoring;
    private final StrategyService strategies;

    ScoreController(ScoringService scoring, StrategyService strategies) {
        this.scoring = scoring;
        this.strategies = strategies;
    }

    private StrategyVersion versionOf(UUID strategyId, Integer version) {
        Strategy strategy = strategies.find(strategyId).orElseThrow(() -> new StrategyException.NotFound("Strategy " + strategyId + " not found"));
        int v = version == null ? strategy.latestVersion() : version;
        return strategies.version(strategyId, v).orElseThrow(() -> new StrategyException.NotFound("Version " + v + " of strategy " + strategyId + " not found"));
    }

    /** The PRD 14 breakdown for one instrument (default: the first scored instrument) plus every instrument's latest final score. */
    @GetMapping("/{id}/score")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    Map<String, Object> score(@PathVariable UUID id, @RequestParam(required = false) Integer version, @RequestParam(required = false) UUID instrumentId) {
        StrategyVersion v = versionOf(id, version);
        List<ScoreBreakdown> all = scoring.latestForVersion(v.id());
        Optional<ScoreBreakdown> chosen = instrumentId != null
                ? all.stream().filter(s -> instrumentId.equals(s.instrumentId())).findFirst()
                : all.stream().max((a, b) -> Integer.compare(a.finalScore(), b.finalScore()));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("strategyId", id);
        out.put("versionId", v.id());
        out.put("version", v.version());
        out.put("breakdown", chosen.orElse(null));
        out.put("instruments", all.stream().map(s -> Map.of("instrumentId", s.instrumentId() == null ? "" : s.instrumentId().toString(),
                "finalScore", s.finalScore(), "computedAt", s.computedAt().toString())).toList());
        return out;
    }

    @PostMapping("/{id}/score/recompute")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    List<ScoreBreakdown> recompute(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return scoring.recomputeVersion(versionOf(id, version).id());
    }

    @GetMapping("/compare")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<ComparisonRow> compare(@RequestParam List<UUID> versionIds) {
        return scoring.compare(versionIds);
    }

    @GetMapping("/{id}/versions/compare")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    VersionComparison compareVersions(@PathVariable UUID id, @RequestParam int a, @RequestParam int b) {
        return scoring.compareVersions(id, a, b);
    }
}
