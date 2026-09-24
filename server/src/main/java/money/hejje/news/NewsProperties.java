package money.hejje.news;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * News settings ({@code hejje.news.*}, docs/news.md).
 *
 * @param enabled        polling and classification (off: nothing is fetched; the bias is unavailable)
 * @param pollMinutes    poll interval during the day
 * @param sources        YAML resource seeding {@code news_source} ({@code sources: [{name, url, kind, reliability, enabled}]})
 * @param aliases        YAML resource mapping instruments to names/tickers/sector for deterministic matching
 * @param window         trailing window of the bias
 * @param halfLife       recency decay half-life
 * @param staleAfter     no successful poll for this long → the bias is unavailable ("news stale")
 * @param minRelevance   assessments below this relevance do not contribute
 * @param strongScore    |score| at or above which the label is STRONGLY_*
 * @param mildScore      |score| at or above which the label is BULLISH / BEARISH
 * @param maxItemsPerPoll cap of new items classified per source per poll (cost control)
 * @param titleSimilarity Jaccard token overlap at or above which two titles within 24 h are the same story
 * @param classifier     {@code llm}, {@code jev} or {@code shadow} (LLM result used, Jev's stored beside it; plan M9.3)
 * @param riskEventThreshold P(risk event today) from Jev at or above which a market-wide macro event is recorded
 * @param marketHeadlineCount newest headlines of the day sent to Jev for the index risk-event check
 */
@ConfigurationProperties("hejje.news")
public record NewsProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5") int pollMinutes,
        @DefaultValue("classpath:news-sources.yaml") String sources,
        @DefaultValue("classpath:aliases.yaml") String aliases,
        @DefaultValue("24h") Duration window,
        @DefaultValue("4h") Duration halfLife,
        @DefaultValue("2h") Duration staleAfter,
        @DefaultValue("0.3") double minRelevance,
        @DefaultValue("0.6") double strongScore,
        @DefaultValue("0.2") double mildScore,
        @DefaultValue("25") int maxItemsPerPoll,
        @DefaultValue("0.8") double titleSimilarity,
        @DefaultValue("30s") Duration fetchTimeout,
        @DefaultValue({}) List<String> ignoredHosts,
        @DefaultValue("llm") String classifier,
        @DefaultValue("0.6") double riskEventThreshold,
        @DefaultValue("15") int marketHeadlineCount) {
}
