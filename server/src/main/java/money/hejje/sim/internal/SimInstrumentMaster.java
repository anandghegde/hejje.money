package money.hejje.sim.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import money.hejje.common.config.HejjeProperties;
import money.hejje.instruments.InstrumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Imports the live instance's instrument master (written by {@code POST /instruments/export} to
 * {@code <data-dir>/instruments/master.json}) at startup, keeping the ids: the Parquet history is keyed by instrument id,
 * so a SIM instance must use the live ids to find it (plan M7.2). Each instrument is mapped to the fake broker.
 */
@Component
@ConditionalOnProperty(name = "hejje.mode", havingValue = "SIM")
class SimInstrumentMaster {

    private static final Logger log = LoggerFactory.getLogger(SimInstrumentMaster.class);

    private final InstrumentService instruments;
    private final HejjeProperties properties;
    private final ObjectMapper json;

    SimInstrumentMaster(InstrumentService instruments, HejjeProperties properties, ObjectMapper json) {
        this.instruments = instruments;
        this.properties = properties;
        this.json = json;
    }

    @EventListener(ApplicationStartedEvent.class)
    void importMaster() throws java.io.IOException {
        Path file = properties.dataDir().resolve("instruments").resolve("master.json");
        if (!Files.exists(file)) {
            log.info("No instrument master at {}: SIM uses the instruments already in its database", file);
            return;
        }
        int n = instruments.importMaster(file, "fake", json);
        log.info("Imported {} instrument(s) from {} with their live ids", n, file);
    }
}
