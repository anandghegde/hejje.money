package money.hejje.recommend.internal;

import java.util.List;
import java.util.UUID;
import money.hejje.recommend.Recommendation;
import money.hejje.recommend.RecommendationService;
import money.hejje.recommend.TodayView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class TodayController {

    private final RecommendationService recommendations;

    TodayController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping("/today")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    TodayView today() {
        return recommendations.today();
    }

    @GetMapping("/today/history")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<Recommendation> history(@RequestParam UUID signalId) {
        return recommendations.history(signalId);
    }
}
