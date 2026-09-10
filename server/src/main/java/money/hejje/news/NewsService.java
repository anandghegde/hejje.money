package money.hejje.news;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Ids;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.MarketService;
import money.hejje.news.internal.Dedupe;
import money.hejje.news.internal.FeedParser;
import money.hejje.news.internal.InstrumentMatcher;
import money.hejje.news.internal.NewsBiasAggregator;
import money.hejje.news.internal.NewsClassifier;
import money.hejje.news.internal.NewsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Public API of the news module: sources, polling/ingest, items, assessments and the per-instrument bias. */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private final NewsStore store;
    private final InstrumentMatcher matcher;
    private final NewsClassifier classifier;
    private final InstrumentService instruments;
    private final MarketService market;
    private final NewsProperties props;
    private final HejjeClock clock;
    private final HttpClient http;

    NewsService(NewsStore store, InstrumentMatcher matcher, NewsClassifier classifier, InstrumentService instruments, MarketService market, NewsProperties props,
            HejjeClock clock) {
        this.store = store;
        this.matcher = matcher;
        this.classifier = classifier;
        this.instruments = instruments;
        this.market = market;
        this.props = props;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(props.fetchTimeout()).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public boolean enabled() {
        return props.enabled();
    }

    // --- sources ---

    public List<NewsSource> sources() {
        return store.sources();
    }

    public NewsSource updateSource(UUID id, Boolean enabled, Double reliability) {
        NewsSource s = store.source(id).orElseThrow(() -> new IllegalArgumentException("Unknown news source " + id));
        store.updateSource(id, enabled == null ? s.enabled() : enabled, reliability == null ? s.reliability() : Math.max(0, Math.min(1, reliability)));
        return store.source(id).orElseThrow();
    }

    public void seedSource(String name, String url, NewsSource.Kind kind, double reliability, boolean enabled) {
        store.upsertSource(name, url, kind, reliability, enabled);
    }

    // --- polling and ingest ---

    public record PollResult(int sources, int fetched, int stored, int assessed, List<String> errors) {}

    /** Fetches every enabled source once. A failing source records its error and the rest continue. */
    public PollResult poll() {
        if (!props.enabled()) {
            return new PollResult(0, 0, 0, 0, List.of("news disabled"));
        }
        int fetched = 0;
        int stored = 0;
        int assessed = 0;
        int count = 0;
        List<String> errors = new ArrayList<>();
        for (NewsSource source : store.sources()) {
            if (!source.enabled()) {
                continue;
            }
            count++;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(source.url())).timeout(props.fetchTimeout()).header("User-Agent", "Mozilla/5.0 (hejje)")
                        .header("Accept", "application/rss+xml, application/atom+xml, application/xml, application/json, text/xml;q=0.9, */*;q=0.8").GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                List<FeedParser.Fetched> items = FeedParser.parse(source.kind(), response.body(), clock.now());
                fetched += items.size();
                IngestResult r = ingest(source.id(), items);
                stored += r.stored();
                assessed += r.assessed();
                store.polled(source.id(), clock.now(), null);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                errors.add(source.name() + ": " + msg);
                store.polled(source.id(), clock.now(), msg);
                log.warn("News source {} failed: {}", source.name(), msg);
            }
        }
        return new PollResult(count, fetched, stored, assessed, errors);
    }

    public record IngestResult(int stored, int duplicates, int assessed) {}

    /** Stores new items (dedupe by URL, content hash and near-identical title within 24 h) and classifies the ones with candidates. */
    public IngestResult ingest(UUID sourceId, List<FeedParser.Fetched> fetched) {
        Instant now = clock.now();
        NewsSource source = store.source(sourceId).orElse(null);
        List<String> recentTitles = new ArrayList<>(store.recentNormTitles(now.minus(Duration.ofHours(24))));
        int stored = 0;
        int duplicates = 0;
        int assessed = 0;
        int classified = 0;
        for (FeedParser.Fetched f : fetched) {
            String norm = Dedupe.normalize(f.title());
            boolean similar = recentTitles.stream().anyMatch(t -> Dedupe.similarity(t, norm) >= props.titleSimilarity());
            if (similar) {
                duplicates++;
                continue;
            }
            NewsItem item = new NewsItem(Ids.newId(), sourceId, f.url(), f.title(), f.summary(), null, f.publishedAt(), now, Dedupe.hash(f.title(), f.summary()), norm);
            Optional<NewsItem> inserted = store.insertItem(item);
            if (inserted.isEmpty()) {
                duplicates++;
                continue;
            }
            stored++;
            recentTitles.add(norm);
            List<InstrumentMatcher.Candidate> candidates = matcher.match(f.title(), f.summary());
            if (!candidates.isEmpty() && classified < props.maxItemsPerPoll()) {
                classified++;
                for (NewsAssessment a : classifier.classify(item, source, candidates, now)) {
                    store.insertAssessment(a);
                    assessed++;
                }
            }
        }
        return new IngestResult(stored, duplicates, assessed);
    }

    // --- reads ---

    public List<NewsItem> items(UUID instrumentId, Instant from, int limit) {
        return store.items(from == null ? clock.now().minus(props.window()) : from, instrumentId, limit);
    }

    public List<NewsAssessment> assessmentsOf(UUID itemId) {
        return store.assessmentsOfItem(itemId);
    }

    /** The bias for an instrument as of now (computed from the retained assessments and stored). Never throws. */
    /**
     * The bias snapshot stored at or before {@code at} within the aggregation window, if any (trade reviews record the
     * news context as of the entry, plan M4.5). Never throws.
     */
    public Optional<NewsBias> biasSnapshotAt(UUID instrumentId, Instant at) {
        try {
            return store.latestBias(instrumentId, at.minus(props.window()), at);
        } catch (RuntimeException e) {
            log.warn("News snapshot lookup failed for {}: {}", instrumentId, e.getMessage());
            return Optional.empty();
        }
    }

    public NewsBias bias(UUID instrumentId) {
        Instant now = clock.now();
        if (!props.enabled()) {
            return NewsBias.unavailable(instrumentId, now, "news unavailable (hejje.news.enabled=false)");
        }
        if (!classifier.available()) {
            return NewsBias.unavailable(instrumentId, now, "news unavailable (LLM disabled)");
        }
        try {
            Optional<Instant> lastPoll = store.lastSuccessfulPoll();
            if (lastPoll.isEmpty() || lastPoll.get().plus(props.staleAfter()).isBefore(now)) {
                return NewsBias.unavailable(instrumentId, now, "news stale (no successful poll in the last " + props.staleAfter().toHours() + " h)");
            }
            List<NewsAssessment> assessments = store.assessments(instrumentId, now.minus(props.window()));
            Map<UUID, NewsItem> items = new HashMap<>();
            Map<UUID, NewsSource> sources = new HashMap<>();
            for (NewsSource s : store.sources()) {
                sources.put(s.id(), s);
            }
            for (NewsAssessment a : assessments) {
                store.item(a.itemId()).ifPresent(i -> items.put(i.id(), i));
            }
            NewsBias draft = NewsBiasAggregator.aggregate(instrumentId, now, assessments, items, sources, props, null);
            NewsBias bias = draft.items() == 0 ? draft
                    : NewsBiasAggregator.aggregate(instrumentId, now, assessments, items, sources, props, reaction(instrumentId, now, draft.score()));
            store.insertBias(bias);
            return bias;
        } catch (RuntimeException e) {
            log.warn("News bias unavailable for {}: {}", instrumentId, e.getMessage());
            return NewsBias.unavailable(instrumentId, now, "news unavailable: " + e.getMessage());
        }
    }

    /** PRD 17.2 price/volume reaction: today's move and relative volume from M5 candles, compared with the bias sign. */
    NewsBiasAggregator.Reaction reaction(UUID instrumentId, Instant now, double score) {
        try {
            ZoneId zone = clock.zone();
            LocalDate today = now.atZone(zone).toLocalDate();
            Instant open = clock.sessionWindow(today).open().toInstant();
            List<Candle> candles = market.candles(instrumentId, Timeframe.M5, today.minusDays(12).atStartOfDay(zone).toInstant(), now);
            Double prevClose = null;
            Double last = null;
            double todayVolume = 0;
            Map<LocalDate, Double> volumeByDay = new HashMap<>();
            for (Candle c : candles) {
                LocalDate d = c.openTime().atZone(zone).toLocalDate();
                if (c.openTime().isBefore(open)) {
                    prevClose = c.close().doubleValue();
                    volumeByDay.merge(d, (double) c.volume(), Double::sum);
                } else {
                    last = c.close().doubleValue();
                    todayVolume += c.volume();
                }
            }
            if (prevClose == null || last == null || prevClose == 0) {
                return new NewsBiasAggregator.Reaction("Price reaction: no session data yet", 0);
            }
            double changePct = 100.0 * (last - prevClose) / prevClose;
            double avgVolume = volumeByDay.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            String vol = avgVolume > 0 ? String.format(Locale.ROOT, " on %.1fx volume", todayVolume / avgVolume) : "";
            int agreement = Math.abs(changePct) < 0.3 || Math.abs(score) < 0.05 ? 0 : (Math.signum(changePct) == Math.signum(score) ? 1 : -1);
            String verdict = agreement > 0 ? "confirms" : agreement < 0 ? "disagrees with" : "does not yet confirm";
            return new NewsBiasAggregator.Reaction(String.format(Locale.ROOT, "Price/volume reaction %s the news (%+.2f%%%s)", verdict, changePct, vol), agreement);
        } catch (RuntimeException e) {
            return new NewsBiasAggregator.Reaction("Price reaction unavailable: " + e.getMessage(), 0);
        }
    }

    /** The sector of a symbol from config/aliases.yaml (for the Context Card's sector row). */
    public Optional<String> sectorOf(String symbol) {
        String upper = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        return matcher.aliases().stream().filter(a -> a.symbol().equals(upper)).map(InstrumentMatcher.Alias::sector).filter(x -> x != null).findFirst();
    }

    public Optional<Instrument> instrument(UUID id) {
        return instruments.findById(id);
    }
}
