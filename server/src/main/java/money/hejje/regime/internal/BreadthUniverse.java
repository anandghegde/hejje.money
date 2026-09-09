package money.hejje.regime.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.regime.RegimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** The breadth constituents: symbols from the configured universe YAML, resolved through the instrument master. */
@Component
class BreadthUniverse {

    private static final Logger log = LoggerFactory.getLogger(BreadthUniverse.class);

    private final RegimeProperties props;
    private final ResourceLoader resources;
    private final InstrumentService instruments;
    private volatile List<String> symbols;

    BreadthUniverse(RegimeProperties props, ResourceLoader resources, InstrumentService instruments) {
        this.props = props;
        this.resources = resources;
        this.instruments = instruments;
    }

    /** Symbols listed in the universe file (empty when the file is missing or unreadable). */
    List<String> symbols() {
        List<String> cached = symbols;
        if (cached != null) {
            return cached;
        }
        List<String> out = new ArrayList<>();
        try {
            Resource resource = resources.getResource(props.universe());
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in);
                    for (JsonNode n : root.path("symbols")) {
                        out.add(n.asText().trim().toUpperCase());
                    }
                }
            } else {
                log.warn("Regime breadth universe {} not found; breadth will be UNKNOWN", props.universe());
            }
        } catch (IOException e) {
            log.warn("Regime breadth universe {} unreadable: {}", props.universe(), e.getMessage());
        }
        symbols = List.copyOf(out);
        return symbols;
    }

    /** Resolved instruments (a symbol missing from the master is simply absent, lowering coverage). */
    List<Instrument> instruments() {
        List<Instrument> out = new ArrayList<>();
        for (String symbol : symbols()) {
            instruments.resolve(symbol).ifPresent(out::add);
        }
        return out;
    }
}
