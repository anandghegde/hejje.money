package money.hejje.scoring.internal;

import java.util.ArrayList;
import java.util.List;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsService;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoreAdjuster;
import money.hejje.scoring.ScoreContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** PRD 14 "news context" (plan M3.4): {@code round(3 × bias score)} bounded −3..+3; 0 with the reason when news is off, stale or the LLM is disabled. */
@Component
@Order(40)
public class NewsContextAdjuster implements ScoreAdjuster {

    private final NewsService news;

    NewsContextAdjuster(NewsService news) {
        this.news = news;
    }

    @Override
    public String name() {
        return "News context";
    }

    @Override
    public int min() {
        return -3;
    }

    @Override
    public int max() {
        return 3;
    }

    @Override
    public Adjustment adjust(ScoreContext ctx) {
        if (ctx.instrumentId() == null) {
            return Adjustment.none(name(), min(), max(), "no instrument");
        }
        NewsBias bias = news.bias(ctx.instrumentId());
        if (!bias.available()) {
            return Adjustment.none(name(), min(), max(), bias.evidence().isEmpty() ? "news unavailable" : bias.evidence().get(0));
        }
        List<String> evidence = new ArrayList<>();
        evidence.add(String.format(java.util.Locale.ROOT, "News bias %s %+.2f over %d items", bias.label(), bias.score(), bias.items()));
        evidence.addAll(bias.evidence());
        return new Adjustment(name(), (int) Math.round(3 * bias.score()), min(), max(), evidence);
    }
}
