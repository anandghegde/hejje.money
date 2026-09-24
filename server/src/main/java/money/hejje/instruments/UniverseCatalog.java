package money.hejje.instruments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Universe files by name ({@code classpath:universe/<name>.yaml}: {@code symbols:} list, optional {@code industry:} map). */
@Component
public class UniverseCatalog {

    private final ResourceLoader resources;
    private final InstrumentService instruments;
    private final Map<String, Universe> cache = new ConcurrentHashMap<>();

    UniverseCatalog(ResourceLoader resources, InstrumentService instruments) {
        this.resources = resources;
        this.instruments = instruments;
    }

    /** The universe called {@code name}, or empty when there is no such file. */
    public Optional<Universe> find(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            return Optional.empty();
        }
        String key = name.toLowerCase();
        Universe cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Resource resource = resources.getResource("classpath:universe/" + key + ".yaml");
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in);
            List<String> symbols = new ArrayList<>();
            for (JsonNode n : root.path("symbols")) {
                symbols.add(n.asText().trim().toUpperCase());
            }
            Map<String, String> industry = new LinkedHashMap<>();
            root.path("industry").fields().forEachRemaining(e -> industry.put(e.getKey().trim().toUpperCase(), e.getValue().asText().trim()));
            Universe universe = new Universe(key, root.path("index").asText(null), List.copyOf(symbols), Map.copyOf(industry));
            cache.put(key, universe);
            return Optional.of(universe);
        } catch (IOException e) {
            throw new IllegalStateException("Universe " + key + " is unreadable: " + e.getMessage(), e);
        }
    }

    /** Resolves every symbol through the instrument master; a symbol the master lacks is reported, not dropped silently. */
    public Universe.Resolved resolve(String name) {
        Universe universe = find(name).orElseThrow(() -> new IllegalArgumentException("Unknown universe " + name));
        List<Instrument> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String symbol : universe.symbols()) {
            instruments.resolve(symbol).ifPresentOrElse(resolved::add, () -> unresolved.add(symbol));
        }
        return new Universe.Resolved(universe, List.copyOf(resolved), List.copyOf(unresolved));
    }
}
