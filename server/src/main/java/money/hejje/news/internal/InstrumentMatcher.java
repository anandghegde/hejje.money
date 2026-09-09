package money.hejje.news.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import money.hejje.news.NewsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Deterministic first pass (PRD 17.2 "instrument relevance"): config/aliases.yaml maps each instrument to company
 * names, tickers and a sector; a whole-word, case-insensitive hit in the title or summary makes it a candidate for the
 * LLM. No candidates → no LLM call.
 */
@Component
public class InstrumentMatcher {

    private static final Logger log = LoggerFactory.getLogger(InstrumentMatcher.class);

    /** One alias entry. */
    public record Alias(String symbol, List<String> names, String sector) {}

    public record Candidate(String symbol, String sector, String matchedOn) {}

    private final NewsProperties props;
    private final ResourceLoader resources;
    private volatile List<Entry> entries;

    private record Entry(Alias alias, List<Pattern> patterns) {}

    InstrumentMatcher(NewsProperties props, ResourceLoader resources) {
        this.props = props;
        this.resources = resources;
    }

    public List<Alias> aliases() {
        return entries().stream().map(Entry::alias).toList();
    }

    public List<Candidate> match(String title, String summary) {
        String text = (title == null ? "" : title) + "\n" + (summary == null ? "" : summary);
        Map<String, Candidate> out = new LinkedHashMap<>();
        for (Entry e : entries()) {
            for (int i = 0; i < e.patterns().size(); i++) {
                if (e.patterns().get(i).matcher(text).find()) {
                    out.putIfAbsent(e.alias().symbol(), new Candidate(e.alias().symbol(), e.alias().sector(), e.alias().names().get(i)));
                    break;
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private List<Entry> entries() {
        List<Entry> cached = entries;
        if (cached != null) {
            return cached;
        }
        List<Entry> out = new ArrayList<>();
        try {
            Resource resource = resources.getResource(props.aliases());
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    for (JsonNode n : new ObjectMapper(new YAMLFactory()).readTree(in).path("instruments")) {
                        List<String> names = new ArrayList<>();
                        for (JsonNode name : n.path("names")) {
                            names.add(name.asText());
                        }
                        String symbol = n.path("symbol").asText().trim().toUpperCase(Locale.ROOT);
                        if (symbol.isEmpty() || names.isEmpty()) {
                            continue;
                        }
                        List<Pattern> patterns = names.stream().map(name -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])",
                                Pattern.CASE_INSENSITIVE)).toList();
                        out.add(new Entry(new Alias(symbol, names, n.path("sector").asText(null)), patterns));
                    }
                }
            } else {
                log.warn("News alias file {} not found; no instrument matching", props.aliases());
            }
        } catch (IOException e) {
            log.warn("News alias file {} unreadable: {}", props.aliases(), e.getMessage());
        }
        entries = List.copyOf(out);
        return entries;
    }
}
