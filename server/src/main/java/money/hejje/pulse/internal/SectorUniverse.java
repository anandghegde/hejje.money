package money.hejje.pulse.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import money.hejje.pulse.PulseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** The sector indices of the Market Pulse from the configured YAML ({@code sectors: [{name, symbol}]}). */
@Component
class SectorUniverse {

    private static final Logger log = LoggerFactory.getLogger(SectorUniverse.class);

    record Sector(String name, String symbol) {}

    private final PulseProperties props;
    private final ResourceLoader resources;
    private volatile List<Sector> sectors;

    SectorUniverse(PulseProperties props, ResourceLoader resources) {
        this.props = props;
        this.resources = resources;
    }

    List<Sector> sectors() {
        List<Sector> cached = sectors;
        if (cached != null) {
            return cached;
        }
        List<Sector> out = new ArrayList<>();
        try {
            Resource resource = resources.getResource(props.sectors());
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    for (JsonNode n : new ObjectMapper(new YAMLFactory()).readTree(in).path("sectors")) {
                        out.add(new Sector(n.path("name").asText(), n.path("symbol").asText().trim().toUpperCase()));
                    }
                }
            } else {
                log.warn("Pulse sector universe {} not found; sector rows will be absent", props.sectors());
            }
        } catch (IOException e) {
            log.warn("Pulse sector universe {} unreadable: {}", props.sectors(), e.getMessage());
        }
        sectors = List.copyOf(out);
        return sectors;
    }
}
