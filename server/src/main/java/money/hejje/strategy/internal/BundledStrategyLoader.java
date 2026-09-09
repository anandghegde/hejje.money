package money.hejje.strategy.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyProperties;
import money.hejje.strategy.StrategyValidationException;
import money.hejje.strategy.StrategyVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads {@code strategies/*.yaml} at startup: a strategy whose slug is unknown is created; a known strategy whose
 * latest definition hash differs gets a new version with change note {@code bundled}. Files that fail validation are
 * logged and skipped so a bad bundled file never blocks startup.
 */
@Component
public class BundledStrategyLoader {

    private static final Logger log = LoggerFactory.getLogger(BundledStrategyLoader.class);

    private final StrategyService strategies;
    private final StrategyProperties properties;

    BundledStrategyLoader(StrategyService strategies, StrategyProperties properties) {
        this.strategies = strategies;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.loadBundled()) {
            return;
        }
        Optional<Path> dir = properties.bundledDirs().stream().map(Path::of).filter(Files::isDirectory).findFirst();
        if (dir.isPresent()) {
            log.info("Loading bundled strategies from {}", dir.get().toAbsolutePath());
            loadDirectory(dir.get());
            return;
        }
        loadClasspath();
    }

    /** Loads every {@code *.yaml} in the directory (sorted by name). Returns the versions created. */
    public List<StrategyVersion> loadDirectory(Path dir) {
        List<StrategyVersion> created = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                load(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8)).ifPresent(created::add);
            }
        } catch (IOException e) {
            log.warn("Cannot read bundled strategies from {}: {}", dir, e.getMessage());
        }
        return created;
    }

    private void loadClasspath() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:strategies/*.yaml");
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    load(resource.getFilename(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            if (resources.length > 0) {
                log.info("Loaded {} bundled strategy file(s) from the classpath", resources.length);
            }
        } catch (IOException e) {
            log.warn("Cannot read bundled strategies from the classpath: {}", e.getMessage());
        }
    }

    /** Applies one bundled file; returns the version created, or empty when nothing changed or the file is invalid. */
    public Optional<StrategyVersion> load(String fileName, String yaml) {
        try {
            StrategyDefinition definition = strategies.parse(yaml);
            Optional<Strategy> existing = strategies.findBySlug(definition.name());
            if (existing.isEmpty()) {
                StrategyVersion v = strategies.create(yaml, StrategyService.BUNDLED_CHANGE_NOTE, "system");
                log.info("Bundled strategy {} created as v{}", definition.name(), v.version());
                return Optional.of(v);
            }
            String hash = strategies.hash(definition);
            if (strategies.versionByHash(existing.get().id(), hash).isPresent()) {
                return Optional.empty();
            }
            StrategyVersion v = strategies.addVersion(existing.get().id(), yaml, StrategyService.BUNDLED_CHANGE_NOTE, "system");
            log.info("Bundled strategy {} updated to v{}", definition.name(), v.version());
            return Optional.of(v);
        } catch (StrategyValidationException e) {
            log.warn("Bundled strategy {} skipped: {}", fileName, e.errors());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Bundled strategy {} skipped: {}", fileName, e.getMessage());
            return Optional.empty();
        }
    }
}
