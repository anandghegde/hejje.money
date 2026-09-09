package money.hejje.news.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.news.NewsAssessment;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsBiasLabel;
import money.hejje.news.NewsItem;
import money.hejje.news.NewsProperties;
import money.hejje.news.NewsSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class NewsUnitTest {

    static final NewsProperties PROPS = new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("hejje.news", NewsProperties.class);
    static final Instant NOW = Instant.parse("2026-09-08T05:00:00Z");
    static final UUID INFY = UUID.randomUUID();
    static final NewsSource ET = new NewsSource(UUID.randomUUID(), "ET", "http://et", NewsSource.Kind.RSS, 0.8, true, NOW, null);
    static final NewsSource MC = new NewsSource(UUID.randomUUID(), "MC", "http://mc", NewsSource.Kind.RSS, 0.6, true, NOW, null);

    @Test
    void dedupeNormalisesHashesAndMeasuresSimilarity() {
        assertThat(Dedupe.normalize("  Infosys Q2 results: profit UP 5%! ")).isEqualTo("infosys q2 results profit up 5");
        assertThat(Dedupe.hash("A b", "c")).isEqualTo(Dedupe.hash("a B!", "C"));
        assertThat(Dedupe.similarity(Dedupe.normalize("Infosys profit up 5% in Q2"), Dedupe.normalize("Infosys profit up 5 percent in Q2"))).isGreaterThan(0.7);
        assertThat(Dedupe.similarity(Dedupe.normalize("Infosys profit up"), Dedupe.normalize("TCS hires 5000"))).isLessThan(0.2);
        assertThat(Dedupe.similarity("", "a")).isZero();
    }

    @Test
    void feedParserHandlesRssAtomAndJson() {
        String rss = """
                <?xml version="1.0"?><rss version="2.0"><channel><title>ET</title>
                <item><title>Infosys wins &amp; large deal</title><link>https://et/1</link><description>&lt;p&gt;Infosys signed a deal.&lt;/p&gt;</description>
                <pubDate>Tue, 08 Sep 2026 09:30:00 +0530</pubDate></item>
                <item><title>No link</title></item></channel></rss>
                """;
        List<FeedParser.Fetched> items = FeedParser.parse(NewsSource.Kind.RSS, rss, NOW);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("Infosys wins & large deal");
        assertThat(items.get(0).summary()).isEqualTo("Infosys signed a deal.");
        assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-08T04:00:00Z"));
        String atom = """
                <feed xmlns="http://www.w3.org/2005/Atom"><entry><title>TCS update</title><link rel="alternate" href="https://mc/2"/>
                <summary>TCS said something</summary><published>2026-09-08T04:30:00Z</published></entry></feed>
                """;
        List<FeedParser.Fetched> atomItems = FeedParser.parse(NewsSource.Kind.ATOM, atom, NOW);
        assertThat(atomItems).hasSize(1);
        assertThat(atomItems.get(0).url()).isEqualTo("https://mc/2");
        assertThat(atomItems.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-08T04:30:00Z"));
        List<FeedParser.Fetched> jsonItems = FeedParser.parse(NewsSource.Kind.JSON, "[{\"title\":\"X\",\"url\":\"https://j/3\",\"summary\":\"s\"}]", NOW);
        assertThat(jsonItems.get(0).publishedAt()).isEqualTo(NOW); // fallback when no date
        // external entities are refused (secure parsing)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FeedParser.parse(NewsSource.Kind.RSS,
                "<!DOCTYPE rss [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&e;</title><link>x</link></item></channel></rss>", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static NewsItem item(UUID id, NewsSource source, String title, Duration age) {
        return new NewsItem(id, source.id(), "https://x/" + id, title, null, null, NOW.minus(age), NOW, "h" + id, Dedupe.normalize(title));
    }

    static NewsAssessment assess(UUID itemId, double relevance, double direction, double materiality, double confidence) {
        return new NewsAssessment(UUID.randomUUID(), itemId, INFY, "IT", relevance, direction, materiality, 1.0, confidence, "CONTRACT", "summary", "m", "v1", NOW);
    }

    @Test
    void aggregationWeightsDecaysConfirmsAndLabels() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        Map<UUID, NewsItem> items = Map.of(a, item(a, ET, "Infosys wins deal", Duration.ZERO), b, item(b, MC, "Infosys deal confirmed", Duration.ofHours(4)),
                c, item(c, ET, "Passing mention", Duration.ofHours(1)));
        Map<UUID, NewsSource> sources = Map.of(ET.id(), ET, MC.id(), MC);
        List<NewsAssessment> assessments = List.of(assess(a, 0.9, 1.0, 0.8, 0.9), assess(b, 0.8, 1.0, 0.5, 0.8), assess(c, 0.1, -1.0, 0.9, 0.9));
        NewsBias bias = NewsBiasAggregator.aggregate(INFY, NOW, assessments, items, sources, PROPS, new NewsBiasAggregator.Reaction("Price/volume reaction confirms the news (+1.20%)", 1));
        // a: 1.0×0.8×0.9×0.8×decay(0)=0.576 ; b: 1.0×0.5×0.8×0.6×0.5 (4 h = one half-life)=0.12 ; c: below min relevance -> ignored
        double expected = Math.min(1, (0.576 + 0.12) * 1.25);
        assertThat(bias.score()).isCloseTo(Math.round(expected * 100) / 100.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(bias.label()).isEqualTo(NewsBiasLabel.STRONGLY_BULLISH);
        assertThat(bias.items()).isEqualTo(2);
        assertThat(bias.available()).isTrue();
        assertThat(bias.evidence()).hasSize(4);
        assertThat(bias.evidence().get(0)).startsWith("▲ summary (ET, 0.0 h ago, materiality 0.8, +0.58)");
        assertThat(bias.evidence().get(2)).isEqualTo("Confirmed by 2 sources (×1.25)");
        assertThat(bias.evidence().get(3)).contains("confirms");
        assertThat(bias.sources().get(0).itemId()).isEqualTo(a);

        // one bearish, low materiality story: mild
        NewsBias mild = NewsBiasAggregator.aggregate(INFY, NOW, List.of(assess(a, 0.9, -1.0, 0.4, 0.8)), items, sources, PROPS, null);
        assertThat(mild.score()).isCloseTo(-0.26, org.assertj.core.data.Offset.offset(1e-9)); // -0.4×0.8×0.8
        assertThat(mild.label()).isEqualTo(NewsBiasLabel.BEARISH);
        NewsBias neutral = NewsBiasAggregator.aggregate(INFY, NOW, List.of(assess(a, 0.9, 0.2, 0.3, 0.5)), items, sources, PROPS, null);
        assertThat(neutral.label()).isEqualTo(NewsBiasLabel.NEUTRAL);
        NewsBias none = NewsBiasAggregator.aggregate(INFY, NOW, List.of(), items, sources, PROPS, null);
        assertThat(none.items()).isZero();
        assertThat(none.evidence().get(0)).isEqualTo("No material news in the last 24 h");
        // clipped
        NewsBias big = NewsBiasAggregator.aggregate(INFY, NOW, List.of(assess(a, 1, 1, 1, 1), assess(b, 1, 1, 1, 1)), items, sources, PROPS, null);
        assertThat(big.score()).isEqualTo(1.0);
        NewsBias unavailable = NewsBias.unavailable(INFY, NOW, "news unavailable (LLM disabled)");
        assertThat(unavailable.label()).isEqualTo(NewsBiasLabel.NEUTRAL);
        assertThat(unavailable.available()).isFalse();
    }
}
